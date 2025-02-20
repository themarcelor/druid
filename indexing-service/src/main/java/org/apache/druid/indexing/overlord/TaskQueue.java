/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.druid.indexing.overlord;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.Sets;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ThreadFactoryBuilder;
import org.apache.druid.indexer.TaskLocation;
import org.apache.druid.indexer.TaskStatus;
import org.apache.druid.indexing.common.Counters;
import org.apache.druid.indexing.common.actions.TaskActionClientFactory;
import org.apache.druid.indexing.common.task.IndexTaskUtils;
import org.apache.druid.indexing.common.task.Task;
import org.apache.druid.indexing.common.task.Tasks;
import org.apache.druid.indexing.common.task.batch.MaxAllowedLocksExceededException;
import org.apache.druid.indexing.common.task.batch.parallel.SinglePhaseParallelIndexTaskRunner;
import org.apache.druid.indexing.overlord.config.DefaultTaskConfig;
import org.apache.druid.indexing.overlord.config.TaskLockConfig;
import org.apache.druid.indexing.overlord.config.TaskQueueConfig;
import org.apache.druid.java.util.common.StringUtils;
import org.apache.druid.java.util.common.concurrent.ScheduledExecutors;
import org.apache.druid.java.util.common.lifecycle.LifecycleStart;
import org.apache.druid.java.util.common.lifecycle.LifecycleStop;
import org.apache.druid.java.util.emitter.EmittingLogger;
import org.apache.druid.java.util.emitter.service.ServiceEmitter;
import org.apache.druid.java.util.emitter.service.ServiceMetricEvent;
import org.apache.druid.metadata.EntryExistsException;
import org.apache.druid.utils.CollectionUtils;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

/**
 * Interface between task producers and the task runner.
 * <p/>
 * This object accepts tasks from producers using {@link #add} and manages delivery of these tasks to a
 * {@link TaskRunner}. Tasks will run in a mostly-FIFO order, with deviations when the natural next task is not ready
 * in time (based on its {@link Task#isReady} method).
 * <p/>
 * For persistence, we save all new tasks and task status changes using a {@link TaskStorage} object.
 */
public class TaskQueue
{
  private final long MANAGEMENT_WAIT_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(60);

  private final List<Task> tasks = new ArrayList<>();
  private final Map<String, ListenableFuture<TaskStatus>> taskFutures = new HashMap<>();

  private final TaskLockConfig lockConfig;
  private final TaskQueueConfig config;
  private final DefaultTaskConfig defaultTaskConfig;
  private final TaskStorage taskStorage;
  private final TaskRunner taskRunner;
  private final TaskActionClientFactory taskActionClientFactory;
  private final TaskLockbox taskLockbox;
  private final ServiceEmitter emitter;

  private final ReentrantLock giant = new ReentrantLock(true);
  private final Condition managementMayBeNecessary = giant.newCondition();
  private final ExecutorService managerExec = Executors.newSingleThreadExecutor(
      new ThreadFactoryBuilder()
          .setDaemon(false)
          .setNameFormat("TaskQueue-Manager").build()
  );
  private final ScheduledExecutorService storageSyncExec = Executors.newSingleThreadScheduledExecutor(
      new ThreadFactoryBuilder()
          .setDaemon(false)
          .setNameFormat("TaskQueue-StorageSync").build()
  );

  private volatile boolean active = false;

  private static final EmittingLogger log = new EmittingLogger(TaskQueue.class);

  private final ConcurrentHashMap<String, AtomicLong> totalSuccessfulTaskCount = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, AtomicLong> totalFailedTaskCount = new ConcurrentHashMap<>();
  private Map<String, Long> prevTotalSuccessfulTaskCount = new HashMap<>();
  private Map<String, Long> prevTotalFailedTaskCount = new HashMap<>();

  public TaskQueue(
      TaskLockConfig lockConfig,
      TaskQueueConfig config,
      DefaultTaskConfig defaultTaskConfig,
      TaskStorage taskStorage,
      TaskRunner taskRunner,
      TaskActionClientFactory taskActionClientFactory,
      TaskLockbox taskLockbox,
      ServiceEmitter emitter
  )
  {
    this.lockConfig = Preconditions.checkNotNull(lockConfig, "lockConfig");
    this.config = Preconditions.checkNotNull(config, "config");
    this.defaultTaskConfig = Preconditions.checkNotNull(defaultTaskConfig, "defaultTaskContextConfig");
    this.taskStorage = Preconditions.checkNotNull(taskStorage, "taskStorage");
    this.taskRunner = Preconditions.checkNotNull(taskRunner, "taskRunner");
    this.taskActionClientFactory = Preconditions.checkNotNull(taskActionClientFactory, "taskActionClientFactory");
    this.taskLockbox = Preconditions.checkNotNull(taskLockbox, "taskLockbox");
    this.emitter = Preconditions.checkNotNull(emitter, "emitter");
  }

  @VisibleForTesting
  void setActive(boolean active)
  {
    this.active = active;
  }

  /**
   * Starts this task queue. Allows {@link #add(Task)} to accept new tasks.
   */
  @LifecycleStart
  public void start()
  {
    giant.lock();

    try {
      Preconditions.checkState(!active, "queue must be stopped");
      active = true;
      syncFromStorage();
      managerExec.submit(
          new Runnable()
          {
            @Override
            public void run()
            {
              while (true) {
                try {
                  manage();
                  break;
                }
                catch (InterruptedException e) {
                  log.info("Interrupted, exiting!");
                  break;
                }
                catch (Exception e) {
                  final long restartDelay = config.getRestartDelay().getMillis();
                  log.makeAlert(e, "Failed to manage").addData("restartDelay", restartDelay).emit();
                  try {
                    Thread.sleep(restartDelay);
                  }
                  catch (InterruptedException e2) {
                    log.info("Interrupted, exiting!");
                    break;
                  }
                }
              }
            }
          }
      );
      ScheduledExecutors.scheduleAtFixedRate(
          storageSyncExec,
          config.getStorageSyncRate(),
          new Callable<ScheduledExecutors.Signal>()
          {
            @Override
            public ScheduledExecutors.Signal call()
            {
              try {
                syncFromStorage();
              }
              catch (Exception e) {
                if (active) {
                  log.makeAlert(e, "Failed to sync with storage").emit();
                }
              }
              if (active) {
                return ScheduledExecutors.Signal.REPEAT;
              } else {
                return ScheduledExecutors.Signal.STOP;
              }
            }
          }
      );
      managementMayBeNecessary.signalAll();
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Shuts down the queue.
   */
  @LifecycleStop
  public void stop()
  {
    giant.lock();

    try {
      tasks.clear();
      taskFutures.clear();
      active = false;
      managerExec.shutdownNow();
      storageSyncExec.shutdownNow();
      managementMayBeNecessary.signalAll();
    }
    finally {
      giant.unlock();
    }
  }

  public boolean isActive()
  {
    return active;
  }

  /**
   * Main task runner management loop. Meant to run forever, or, at least until we're stopped.
   */
  private void manage() throws InterruptedException
  {
    log.info("Beginning management in %s.", config.getStartDelay());
    Thread.sleep(config.getStartDelay().getMillis());

    // Ignore return value- we'll get the IDs and futures from getKnownTasks later.
    taskRunner.restore();

    while (active) {
      giant.lock();

      try {
        manageInternal();
        // awaitNanos because management may become necessary without this condition signalling,
        // due to e.g. tasks becoming ready when other folks mess with the TaskLockbox.
        managementMayBeNecessary.awaitNanos(MANAGEMENT_WAIT_TIMEOUT_NANOS);
      }
      finally {
        giant.unlock();
      }
    }
  }

  @VisibleForTesting
  void manageInternal()
  {
    // Task futures available from the taskRunner
    final Map<String, ListenableFuture<TaskStatus>> runnerTaskFutures = new HashMap<>();
    for (final TaskRunnerWorkItem workItem : taskRunner.getKnownTasks()) {
      runnerTaskFutures.put(workItem.getTaskId(), workItem.getResult());
    }
    // Attain futures for all active tasks (assuming they are ready to run).
    // Copy tasks list, as notifyStatus may modify it.
    for (final Task task : ImmutableList.copyOf(tasks)) {
      if (!taskFutures.containsKey(task.getId())) {
        final ListenableFuture<TaskStatus> runnerTaskFuture;
        if (runnerTaskFutures.containsKey(task.getId())) {
          runnerTaskFuture = runnerTaskFutures.get(task.getId());
        } else {
          // Task should be running, so run it.
          final boolean taskIsReady;
          try {
            taskIsReady = task.isReady(taskActionClientFactory.create(task));
          }
          catch (Exception e) {
            log.warn(e, "Exception thrown during isReady for task: %s", task.getId());
            final String errorMessage;
            if (e instanceof MaxAllowedLocksExceededException) {
              errorMessage = e.getMessage();
            } else {
              errorMessage = "Failed while waiting for the task to be ready to run. "
                                          + "See overlord logs for more details.";
            }
            notifyStatus(task, TaskStatus.failure(task.getId(), errorMessage), errorMessage);
            continue;
          }
          if (taskIsReady) {
            log.info("Asking taskRunner to run: %s", task.getId());
            runnerTaskFuture = taskRunner.run(task);
          } else {
            // Task.isReady() can internally lock intervals or segments.
            // We should release them if the task is not ready.
            taskLockbox.unlockAll(task);
            continue;
          }
        }
        taskFutures.put(task.getId(), attachCallbacks(task, runnerTaskFuture));
      } else if (isTaskPending(task)) {
        // if the taskFutures contain this task and this task is pending, also let the taskRunner
        // to run it to guarantee it will be assigned to run
        // see https://github.com/apache/druid/pull/6991
        taskRunner.run(task);
      }
    }
    // Kill tasks that shouldn't be running
    final Set<String> knownTaskIds = tasks
        .stream()
        .map(Task::getId)
        .collect(Collectors.toSet());
    final Set<String> tasksToKill = Sets.difference(runnerTaskFutures.keySet(), knownTaskIds);
    if (!tasksToKill.isEmpty()) {
      log.info("Asking taskRunner to clean up %,d tasks.", tasksToKill.size());
      for (final String taskId : tasksToKill) {
        try {
          taskRunner.shutdown(
              taskId,
              "task is not in knownTaskIds[%s]",
              knownTaskIds
          );
        }
        catch (Exception e) {
          log.warn(e, "TaskRunner failed to clean up task: %s", taskId);
        }
      }
    }
  }

  private boolean isTaskPending(Task task)
  {
    return taskRunner.getPendingTasks()
                     .stream()
                     .anyMatch(workItem -> workItem.getTaskId().equals(task.getId()));
  }

  /**
   * Adds some work to the queue and the underlying task storage facility with a generic "running" status.
   *
   * @param task task to add
   *
   * @return true
   *
   * @throws EntryExistsException if the task already exists
   */
  public boolean add(final Task task) throws EntryExistsException
  {
    if (taskStorage.getTask(task.getId()).isPresent()) {
      throw new EntryExistsException(StringUtils.format("Task %s already exists", task.getId()));
    }

    // Set forceTimeChunkLock before adding task spec to taskStorage, so that we can see always consistent task spec.
    task.addToContextIfAbsent(Tasks.FORCE_TIME_CHUNK_LOCK_KEY, lockConfig.isForceTimeChunkLock());
    defaultTaskConfig.getContext().forEach(task::addToContextIfAbsent);
    // Every task shuold use the lineage-based segment allocation protocol unless it is explicitly set to
    // using the legacy protocol.
    task.addToContextIfAbsent(
        SinglePhaseParallelIndexTaskRunner.CTX_USE_LINEAGE_BASED_SEGMENT_ALLOCATION_KEY,
        SinglePhaseParallelIndexTaskRunner.DEFAULT_USE_LINEAGE_BASED_SEGMENT_ALLOCATION
    );

    giant.lock();

    try {
      Preconditions.checkState(active, "Queue is not active!");
      Preconditions.checkNotNull(task, "task");
      Preconditions.checkState(tasks.size() < config.getMaxSize(), "Too many tasks (max = %,d)", config.getMaxSize());

      // If this throws with any sort of exception, including TaskExistsException, we don't want to
      // insert the task into our queue. So don't catch it.
      taskStorage.insert(task, TaskStatus.running(task.getId()));
      addTaskInternal(task);
      managementMayBeNecessary.signalAll();
      return true;
    }
    finally {
      giant.unlock();
    }
  }

  // Should always be called after taking giantLock
  private void addTaskInternal(final Task task)
  {
    tasks.add(task);
    taskLockbox.add(task);
  }

  // Should always be called after taking giantLock
  private void removeTaskInternal(final Task task)
  {
    taskLockbox.remove(task);
    tasks.remove(task);
  }

  /**
   * Shuts down a task if it has not yet finished.
   *
   * @param taskId task to kill
   * @param reasonFormat A format string indicating the shutdown reason
   * @param args arguments for reasonFormat
   */
  public void shutdown(final String taskId, String reasonFormat, Object... args)
  {
    giant.lock();

    try {
      Preconditions.checkNotNull(taskId, "taskId");
      for (final Task task : tasks) {
        if (task.getId().equals(taskId)) {
          notifyStatus(task, TaskStatus.failure(taskId, StringUtils.format(reasonFormat, args)), reasonFormat, args);
          break;
        }
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Shuts down a task, but records the task status as a success, unike {@link #shutdown(String, String, Object...)}
   *
   * @param taskId task to shutdown
   * @param reasonFormat A format string indicating the shutdown reason
   * @param args arguments for reasonFormat
   */
  public void shutdownWithSuccess(final String taskId, String reasonFormat, Object... args)
  {
    giant.lock();

    try {
      Preconditions.checkNotNull(taskId, "taskId");
      for (final Task task : tasks) {
        if (task.getId().equals(taskId)) {
          notifyStatus(task, TaskStatus.success(taskId), reasonFormat, args);
          break;
        }
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Notify this queue that some task has an updated status. If this update is valid, the status will be persisted in
   * the task storage facility. If the status is a completed status, the task will be unlocked and no further
   * updates will be accepted.
   *
   * @param task       task to update
   * @param taskStatus new task status
   *
   * @throws NullPointerException     if task or status is null
   * @throws IllegalArgumentException if the task ID does not match the status ID
   * @throws IllegalStateException    if this queue is currently shut down
   */
  private void notifyStatus(final Task task, final TaskStatus taskStatus, String reasonFormat, Object... args)
  {
    giant.lock();

    TaskLocation taskLocation = TaskLocation.unknown();

    try {
      Preconditions.checkNotNull(task, "task");
      Preconditions.checkNotNull(taskStatus, "status");
      Preconditions.checkState(active, "Queue is not active!");
      Preconditions.checkArgument(
          task.getId().equals(taskStatus.getId()),
          "Mismatching task ids[%s/%s]",
          task.getId(),
          taskStatus.getId()
      );
      // Inform taskRunner that this task can be shut down
      try {
        taskLocation = taskRunner.getTaskLocation(task.getId());
        taskRunner.shutdown(task.getId(), reasonFormat, args);
      }
      catch (Exception e) {
        log.warn(e, "TaskRunner failed to cleanup task after completion: %s", task.getId());
      }
      // Remove from running tasks
      int removed = 0;
      for (int i = tasks.size() - 1; i >= 0; i--) {
        if (tasks.get(i).getId().equals(task.getId())) {
          removed++;
          removeTaskInternal(tasks.get(i));
          break;
        }
      }
      if (removed == 0) {
        log.warn("Unknown task completed: %s", task.getId());
      } else if (removed > 1) {
        log.makeAlert("Removed multiple copies of task").addData("count", removed).addData("task", task.getId()).emit();
      }
      // Remove from futures list
      taskFutures.remove(task.getId());
      if (removed > 0) {
        // If we thought this task should be running, save status to DB
        try {
          final Optional<TaskStatus> previousStatus = taskStorage.getStatus(task.getId());
          if (!previousStatus.isPresent() || !previousStatus.get().isRunnable()) {
            log.makeAlert("Ignoring notification for already-complete task").addData("task", task.getId()).emit();
          } else {
            taskStorage.setStatus(taskStatus.withLocation(taskLocation));
            log.info("Task done: %s", task);
            managementMayBeNecessary.signalAll();
          }
        }
        catch (Exception e) {
          log.makeAlert(e, "Failed to persist status for task")
             .addData("task", task.getId())
             .addData("statusCode", taskStatus.getStatusCode())
             .emit();
        }
      }
    }
    finally {
      giant.unlock();
    }
  }

  /**
   * Attach success and failure handlers to a task status future, such that when it completes, we perform the
   * appropriate updates.
   *
   * @param statusFuture a task status future
   *
   * @return the same future, for convenience
   */
  private ListenableFuture<TaskStatus> attachCallbacks(final Task task, final ListenableFuture<TaskStatus> statusFuture)
  {
    final ServiceMetricEvent.Builder metricBuilder = new ServiceMetricEvent.Builder();
    IndexTaskUtils.setTaskDimensions(metricBuilder, task);

    Futures.addCallback(
        statusFuture,
        new FutureCallback<TaskStatus>()
        {
          @Override
          public void onSuccess(final TaskStatus status)
          {
            log.info("Received %s status for task: %s", status.getStatusCode(), status.getId());
            handleStatus(status);
          }

          @Override
          public void onFailure(final Throwable t)
          {
            log.makeAlert(t, "Failed to run task")
               .addData("task", task.getId())
               .addData("type", task.getType())
               .addData("dataSource", task.getDataSource())
               .emit();
            handleStatus(
                TaskStatus.failure(task.getId(), "Failed to run this task. See overlord logs for more details.")
            );
          }

          private void handleStatus(final TaskStatus status)
          {
            try {
              // If we're not supposed to be running anymore, don't do anything. Somewhat racey if the flag gets set
              // after we check and before we commit the database transaction, but better than nothing.
              if (!active) {
                log.info("Abandoning task due to shutdown: %s", task.getId());
                return;
              }

              notifyStatus(task, status, "notified status change from task");

              // Emit event and log, if the task is done
              if (status.isComplete()) {
                IndexTaskUtils.setTaskStatusDimensions(metricBuilder, status);
                emitter.emit(metricBuilder.build("task/run/time", status.getDuration()));

                log.info(
                    "Task %s: %s (%d run duration)",
                    status.getStatusCode(),
                    task,
                    status.getDuration()
                );

                if (status.isSuccess()) {
                  Counters.incrementAndGetLong(totalSuccessfulTaskCount, task.getDataSource());
                } else {
                  Counters.incrementAndGetLong(totalFailedTaskCount, task.getDataSource());
                }
              }
            }
            catch (Exception e) {
              log.makeAlert(e, "Failed to handle task status")
                 .addData("task", task.getId())
                 .addData("statusCode", status.getStatusCode())
                 .emit();
            }
          }
        }
    );
    return statusFuture;
  }

  /**
   * Resync the contents of this task queue with our storage facility. Useful to make sure our in-memory state
   * corresponds to the storage facility even if the latter is manually modified.
   */
  private void syncFromStorage()
  {
    giant.lock();

    try {
      if (active) {
        final Map<String, Task> newTasks = toTaskIDMap(taskStorage.getActiveTasks());
        final int tasksSynced = newTasks.size();
        final Map<String, Task> oldTasks = toTaskIDMap(tasks);

        // Calculate differences on IDs instead of Task Objects.
        Set<String> commonIds = Sets.newHashSet(Sets.intersection(newTasks.keySet(), oldTasks.keySet()));
        for (String taskID : commonIds) {
          newTasks.remove(taskID);
          oldTasks.remove(taskID);
        }
        Collection<Task> addedTasks = newTasks.values();
        Collection<Task> removedTasks = oldTasks.values();

        // Clean up removed Tasks
        for (Task task : removedTasks) {
          removeTaskInternal(task);
        }

        // Add newly Added tasks to the queue
        for (Task task : addedTasks) {
          addTaskInternal(task);
        }

        log.info(
            "Synced %d tasks from storage (%d tasks added, %d tasks removed).",
            tasksSynced,
            addedTasks.size(),
            removedTasks.size()
        );
        managementMayBeNecessary.signalAll();
      } else {
        log.info("Not active. Skipping storage sync.");
      }
    }
    catch (Exception e) {
      log.warn(e, "Failed to sync tasks from storage!");
      throw new RuntimeException(e);
    }
    finally {
      giant.unlock();
    }
  }

  private static Map<String, Task> toTaskIDMap(List<Task> taskList)
  {
    Map<String, Task> rv = new HashMap<>();
    for (Task task : taskList) {
      rv.put(task.getId(), task);
    }
    return rv;
  }

  private Map<String, Long> getDeltaValues(Map<String, Long> total, Map<String, Long> prev)
  {
    return total.entrySet()
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> e.getValue() - prev.getOrDefault(e.getKey(), 0L)));
  }

  public Map<String, Long> getSuccessfulTaskCount()
  {
    Map<String, Long> total = CollectionUtils.mapValues(totalSuccessfulTaskCount, AtomicLong::get);
    Map<String, Long> delta = getDeltaValues(total, prevTotalSuccessfulTaskCount);
    prevTotalSuccessfulTaskCount = total;
    return delta;
  }

  public Map<String, Long> getFailedTaskCount()
  {
    Map<String, Long> total = CollectionUtils.mapValues(totalFailedTaskCount, AtomicLong::get);
    Map<String, Long> delta = getDeltaValues(total, prevTotalFailedTaskCount);
    prevTotalFailedTaskCount = total;
    return delta;
  }

  public Map<String, Long> getRunningTaskCount()
  {
    Map<String, String> taskDatasources = tasks.stream().collect(Collectors.toMap(Task::getId, Task::getDataSource));
    return taskRunner.getRunningTasks()
                     .stream()
                     .collect(Collectors.toMap(
                         e -> taskDatasources.getOrDefault(e.getTaskId(), ""),
                         e -> 1L,
                         Long::sum
                     ));
  }

  public Map<String, Long> getPendingTaskCount()
  {
    Map<String, String> taskDatasources = tasks.stream().collect(Collectors.toMap(Task::getId, Task::getDataSource));
    return taskRunner.getPendingTasks()
                     .stream()
                     .collect(Collectors.toMap(
                         e -> taskDatasources.getOrDefault(e.getTaskId(), ""),
                         e -> 1L,
                         Long::sum
                     ));
  }

  public Map<String, Long> getWaitingTaskCount()
  {
    Set<String> runnerKnownTaskIds = taskRunner.getKnownTasks()
                                               .stream()
                                               .map(TaskRunnerWorkItem::getTaskId)
                                               .collect(Collectors.toSet());
    return tasks.stream().filter(task -> !runnerKnownTaskIds.contains(task.getId()))
                .collect(Collectors.toMap(Task::getDataSource, task -> 1L, Long::sum));
  }

  @VisibleForTesting
  List<Task> getTasks()
  {
    return tasks;
  }
}
