/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.runtime.tasks;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.CheckpointMetaData;
import org.apache.flink.runtime.checkpoint.CheckpointMetrics;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.checkpoint.StateObjectCollection;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriter.ChannelStateWriteResult;
import org.apache.flink.runtime.checkpoint.channel.ChannelStateWriterImpl;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.io.network.api.CancelCheckpointMarker;
import org.apache.flink.runtime.io.network.api.CheckpointBarrier;
import org.apache.flink.runtime.io.network.api.writer.ResultPartitionWriter;
import org.apache.flink.runtime.io.network.buffer.Buffer;
import org.apache.flink.runtime.io.network.partition.ResultSubpartition;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.state.CheckpointStorageLocationReference;
import org.apache.flink.runtime.state.CheckpointStorageWorkerView;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.SnapshotResult;
import org.apache.flink.streaming.api.operators.OperatorSnapshotFutures;
import org.apache.flink.streaming.api.operators.StreamOperator;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkRuntimeException;
import org.apache.flink.util.IOUtils;
import org.apache.flink.util.function.BiFunctionWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.function.Consumer;
import java.util.function.Supplier;

import static org.apache.flink.util.Preconditions.checkNotNull;

class SubtaskCheckpointCoordinatorImpl implements SubtaskCheckpointCoordinator {

	private static final Logger LOG = LoggerFactory.getLogger(SubtaskCheckpointCoordinatorImpl.class);
	private static final int DEFAULT_MAX_RECORD_ABORTED_CHECKPOINTS = 128;

	private final CachingCheckpointStorageWorkerView checkpointStorage;
	private final String taskName;
	private final ExecutorService executorService;
	private final Environment env;
	private final AsyncExceptionHandler asyncExceptionHandler;
	private final ChannelStateWriter channelStateWriter;
	private final StreamTaskActionExecutor actionExecutor;
	private final BiFunctionWithException<ChannelStateWriter, Long, CompletableFuture<Void>, IOException> prepareInputSnapshot;
	/**
	 * The IDs of the checkpoint for which we are notified aborted.
	 */
	private final Set<Long> abortedCheckpointIds;
	private long lastCheckpointId;

	/**
	 * Lock that guards state of AsyncCheckpointRunnable registry.
	 **/
	private final Object lock;

	@GuardedBy("lock")
	private final Map<Long, AsyncCheckpointRunnable> checkpoints;

	/**
	 * Indicates if this registry is closed.
	 */
	@GuardedBy("lock")
	private boolean closed;

	SubtaskCheckpointCoordinatorImpl(CheckpointStorageWorkerView checkpointStorage, String taskName, StreamTaskActionExecutor actionExecutor,
		CloseableRegistry closeableRegistry, ExecutorService executorService, Environment env, AsyncExceptionHandler asyncExceptionHandler,
		boolean unalignedCheckpointEnabled,
		BiFunctionWithException<ChannelStateWriter, Long, CompletableFuture<Void>, IOException> prepareInputSnapshot) throws IOException {

		/*************************************************
		 *
		 *  注释： 构建 SubtaskCheckpointCoordinatorImpl
		 */
		this(checkpointStorage, taskName, actionExecutor, closeableRegistry, executorService, env, asyncExceptionHandler,
			unalignedCheckpointEnabled, prepareInputSnapshot, DEFAULT_MAX_RECORD_ABORTED_CHECKPOINTS);
	}

	SubtaskCheckpointCoordinatorImpl(CheckpointStorageWorkerView checkpointStorage, String taskName, StreamTaskActionExecutor actionExecutor,
		CloseableRegistry closeableRegistry, ExecutorService executorService, Environment env, AsyncExceptionHandler asyncExceptionHandler,
		boolean unalignedCheckpointEnabled,
		BiFunctionWithException<ChannelStateWriter, Long, CompletableFuture<Void>, IOException> prepareInputSnapshot,
		int maxRecordAbortedCheckpoints) throws IOException {

		/*************************************************
		 *
		 *  注释： 创建 SubtaskCheckpointCoordinatorImpl 实例对象
		 */
		this(checkpointStorage, taskName, actionExecutor, closeableRegistry, executorService, env, asyncExceptionHandler, prepareInputSnapshot,
			maxRecordAbortedCheckpoints,

			/*************************************************
			 *
			 *  注释： 注意这儿
			 *  1、如果启动 at least once, 则走  第一个分支 ChannelStateWriterImpl
			 *  2、如果启用 exactly once, 则走 第二个分支 NoOpChannelStateWriter
			 */
			unalignedCheckpointEnabled ? openChannelStateWriter(taskName, checkpointStorage) : ChannelStateWriter.NO_OP);
	}

	@VisibleForTesting
	SubtaskCheckpointCoordinatorImpl(CheckpointStorageWorkerView checkpointStorage, String taskName, StreamTaskActionExecutor actionExecutor,
		CloseableRegistry closeableRegistry, ExecutorService executorService, Environment env, AsyncExceptionHandler asyncExceptionHandler,
		BiFunctionWithException<ChannelStateWriter, Long, CompletableFuture<Void>, IOException> prepareInputSnapshot,
		int maxRecordAbortedCheckpoints, ChannelStateWriter channelStateWriter) throws IOException {

		this.checkpointStorage = new CachingCheckpointStorageWorkerView(checkNotNull(checkpointStorage));
		this.taskName = checkNotNull(taskName);
		this.checkpoints = new HashMap<>();
		this.lock = new Object();
		this.executorService = checkNotNull(executorService);
		this.env = checkNotNull(env);
		this.asyncExceptionHandler = checkNotNull(asyncExceptionHandler);
		this.actionExecutor = checkNotNull(actionExecutor);
		this.channelStateWriter = checkNotNull(channelStateWriter);
		this.prepareInputSnapshot = prepareInputSnapshot;
		this.abortedCheckpointIds = createAbortedCheckpointSetWithLimitSize(maxRecordAbortedCheckpoints);
		this.lastCheckpointId = -1L;
		closeableRegistry.registerCloseable(this);
		this.closed = false;
	}

	private static ChannelStateWriter openChannelStateWriter(String taskName, CheckpointStorageWorkerView checkpointStorage) {

		/*************************************************
		 *
		 *  注释： 初始化 ChannelStateWriterImpl
		 */
		ChannelStateWriterImpl writer = new ChannelStateWriterImpl(taskName, checkpointStorage);
		writer.open();
		return writer;
	}

	@Override
	public void abortCheckpointOnBarrier(long checkpointId, Throwable cause, OperatorChain<?, ?> operatorChain) throws IOException {
		LOG.debug("Aborting checkpoint via cancel-barrier {} for task {}", checkpointId, taskName);
		lastCheckpointId = Math.max(lastCheckpointId, checkpointId);
		Iterator<Long> iterator = abortedCheckpointIds.iterator();
		while(iterator.hasNext()) {
			long next = iterator.next();
			if(next < lastCheckpointId) {
				iterator.remove();
			} else {
				break;
			}
		}

		checkpointStorage.clearCacheFor(checkpointId);

		channelStateWriter.abort(checkpointId, cause, true);

		// notify the coordinator that we decline this checkpoint
		env.declineCheckpoint(checkpointId, cause);

		// notify all downstream operators that they should not wait for a barrier from us
		actionExecutor.runThrowing(() -> operatorChain.broadcastEvent(new CancelCheckpointMarker(checkpointId)));
	}

	@Override
	public CheckpointStorageWorkerView getCheckpointStorage() {
		return checkpointStorage;
	}

	@Override
	public ChannelStateWriter getChannelStateWriter() {
		return channelStateWriter;
	}

	@Override
	public void checkpointState(CheckpointMetaData metadata, CheckpointOptions options, CheckpointMetrics metrics,
		OperatorChain<?, ?> operatorChain, Supplier<Boolean> isCanceled) throws Exception {

		checkNotNull(options);
		checkNotNull(metrics);

		// TODO_MA 注释： 从 barriers和records/watermarks/timers/callbacks 的角度来看，以下所有步骤都是原子步骤。
		// All of the following steps happen as an atomic step from the perspective of barriers and records/watermarks/timers/callbacks.
		// TODO_MA 注释： 我们通常尝试尽快发出 checkpoint barrier，以不影响下游检查点对齐
		// We generally try to emit the checkpoint barrier as soon as possible to not affect downstream checkpoint alignments

		// TODO_MA 注释： 进行 checkpointID 校验
		if(lastCheckpointId >= metadata.getCheckpointId()) {
			LOG.info("Out of order checkpoint barrier (aborted previously?): {} >= {}", lastCheckpointId, metadata.getCheckpointId());
			channelStateWriter.abort(metadata.getCheckpointId(), new CancellationException(), true);
			checkAndClearAbortedStatus(metadata.getCheckpointId());
			return;
		}

		// TODO_MA 注释： 记录最后触发的 checkpointId，并在必要时中止 checkpoint 的同步阶段。
		// Step (0): Record the last triggered checkpointId and abort the sync phase of checkpoint if necessary.
		lastCheckpointId = metadata.getCheckpointId();
		if(checkAndClearAbortedStatus(metadata.getCheckpointId())) {
			// broadcast cancel checkpoint marker to avoid downstream back-pressure due to checkpoint barrier align.
			operatorChain.broadcastEvent(new CancelCheckpointMarker(metadata.getCheckpointId()));
			LOG.info("Checkpoint {} has been notified as aborted, would not trigger any checkpoint.", metadata.getCheckpointId());
			return;
		}

		/*************************************************
		 *
		 *  注释： 第一步
		 *  向下游发送 Barrier 前，给当前 Task 的每个 operator 进行逻辑处理的机会。
		 *  这里会调用当前 Task 中所有 operator 的 prepareSnapshotPreBarrier() 方法
		 */
		// Step (1): Prepare the checkpoint, allow operators to do some pre-barrier work.
		//           The pre-barrier work should be nothing or minimal in the common case.
		operatorChain.prepareSnapshotPreBarrier(metadata.getCheckpointId());

		/*************************************************
		 *
		 *  注释： 第二步
		 *  生成 Barrier 并向下游广播 checkpoint Barrier 消息，下游 Task 收到该消息后就开始进行自己的 checkpoint 流程
		 */
		// Step (2): Send the checkpoint barrier downstream
		operatorChain.broadcastEvent(new CheckpointBarrier(metadata.getCheckpointId(), metadata.getTimestamp(), options),
			options.isUnalignedCheckpoint());

		/*************************************************
		 *
		 *  注释： 第三步
		 *  如果是非对齐 checkpoint
		 *  准备溢写 in-flight buffers 为了 input 和 output
		 */
		// Step (3): Prepare to spill the in-flight buffers for input and output
		if(options.isUnalignedCheckpoint()) {
			prepareInflightDataSnapshot(metadata.getCheckpointId());
		}

		/*************************************************
		 *
		 *  注释： 第四步
		 */
		// Step (4): Take the state snapshot. This should be largely asynchronous, to not impact progress of the streaming topology
		Map<OperatorID, OperatorSnapshotFutures> snapshotFutures = new HashMap<>(operatorChain.getNumberOfOperators());
		try {
			/*************************************************
			 *
			 *  注释： 拍摄快照
			 */
			if(takeSnapshotSync(snapshotFutures, metadata, metrics, options, operatorChain, isCanceled)) {

				/*************************************************
				 *
				 *  注释：如果 Checkpoint 执行成功，AsyncCheckpointRunnable 最后会调用 TaskStateManagerImpl
				 *  的 reportTaskStateSnapshots 方法向 JobManager 发送 AcknowledgeCheckpoint 消息。
				 */
				finishAndReportAsync(snapshotFutures, metadata, metrics, options);
			} else {
				cleanup(snapshotFutures, metadata, metrics, new Exception("Checkpoint declined"));
			}
		} catch(Exception ex) {
			cleanup(snapshotFutures, metadata, metrics, ex);
			throw ex;
		}
	}

	@Override
	public void notifyCheckpointComplete(long checkpointId, OperatorChain<?, ?> operatorChain, Supplier<Boolean> isRunning) throws Exception {
		if(isRunning.get()) {
			LOG.debug("Notification of complete checkpoint for task {}", taskName);

			/*************************************************
			 *
			 *  注释： Operator checkpoint 成功
			 */
			for(StreamOperatorWrapper<?, ?> operatorWrapper : operatorChain.getAllOperators(true)) {
				operatorWrapper.notifyCheckpointComplete(checkpointId);
			}
		} else {
			LOG.debug("Ignoring notification of complete checkpoint for not-running task {}", taskName);
		}

		/*************************************************
		 *
		 *  注释： 通知 TaskStateManager
		 */
		env.getTaskStateManager().notifyCheckpointComplete(checkpointId);
	}

	@Override
	public void notifyCheckpointAborted(long checkpointId, OperatorChain<?, ?> operatorChain, Supplier<Boolean> isRunning) throws Exception {

		Exception previousException = null;
		if(isRunning.get()) {
			LOG.debug("Notification of aborted checkpoint for task {}", taskName);

			boolean canceled = cancelAsyncCheckpointRunnable(checkpointId);

			if(!canceled) {
				if(checkpointId > lastCheckpointId) {
					// only record checkpoints that have not triggered on task side.
					abortedCheckpointIds.add(checkpointId);
				}
			}

			channelStateWriter.abort(checkpointId, new CancellationException("checkpoint aborted via notification"), false);

			for(StreamOperatorWrapper<?, ?> operatorWrapper : operatorChain.getAllOperators(true)) {
				try {
					operatorWrapper.getStreamOperator().notifyCheckpointAborted(checkpointId);
				} catch(Exception e) {
					previousException = e;
				}
			}

		} else {
			LOG.debug("Ignoring notification of aborted checkpoint for not-running task {}", taskName);
		}

		env.getTaskStateManager().notifyCheckpointAborted(checkpointId);
		ExceptionUtils.tryRethrowException(previousException);
	}

	@Override
	public void initCheckpoint(long id, CheckpointOptions checkpointOptions) {

		// TODO_MA 注释： 如果未对齐
		if(checkpointOptions.isUnalignedCheckpoint()) {

			/*************************************************
			 *
			 *  注释： 初始化
			 *  channelStateWriter = NoOpChannelStateWriter
			 */
			channelStateWriter.start(id, checkpointOptions);
		}
	}

	@Override
	public void close() throws IOException {
		List<AsyncCheckpointRunnable> asyncCheckpointRunnables = null;
		synchronized(lock) {
			if(!closed) {
				closed = true;
				asyncCheckpointRunnables = new ArrayList<>(checkpoints.values());
				checkpoints.clear();
			}
		}
		IOUtils.closeAllQuietly(asyncCheckpointRunnables);
		channelStateWriter.close();
	}

	@VisibleForTesting
	int getAsyncCheckpointRunnableSize() {
		synchronized(lock) {
			return checkpoints.size();
		}
	}

	@VisibleForTesting
	int getAbortedCheckpointSize() {
		return abortedCheckpointIds.size();
	}

	private boolean checkAndClearAbortedStatus(long checkpointId) {
		return abortedCheckpointIds.remove(checkpointId);
	}

	private void registerAsyncCheckpointRunnable(long checkpointId, AsyncCheckpointRunnable asyncCheckpointRunnable) throws IOException {
		StringBuilder exceptionMessage = new StringBuilder("Cannot register Closeable, ");
		synchronized(lock) {
			if(!closed) {
				if(!checkpoints.containsKey(checkpointId)) {
					checkpoints.put(checkpointId, asyncCheckpointRunnable);
					return;
				} else {
					exceptionMessage.append("async checkpoint ").append(checkpointId).append(" runnable has been register. ");
				}
			} else {
				exceptionMessage.append("this subtaskCheckpointCoordinator is already closed. ");
			}
		}

		IOUtils.closeQuietly(asyncCheckpointRunnable);
		throw new IOException(exceptionMessage.append("Closing argument.").toString());
	}

	private boolean unregisterAsyncCheckpointRunnable(long checkpointId) {
		synchronized(lock) {
			return checkpoints.remove(checkpointId) != null;
		}
	}

	/**
	 * Cancel the async checkpoint runnable with given checkpoint id.
	 * If given checkpoint id is not registered, return false, otherwise return true.
	 */
	private boolean cancelAsyncCheckpointRunnable(long checkpointId) {
		AsyncCheckpointRunnable asyncCheckpointRunnable;
		synchronized(lock) {
			asyncCheckpointRunnable = checkpoints.remove(checkpointId);
		}
		IOUtils.closeQuietly(asyncCheckpointRunnable);
		return asyncCheckpointRunnable != null;
	}

	private void cleanup(Map<OperatorID, OperatorSnapshotFutures> operatorSnapshotsInProgress, CheckpointMetaData metadata,
		CheckpointMetrics metrics, Exception ex) {

		channelStateWriter.abort(metadata.getCheckpointId(), ex, true);
		for(OperatorSnapshotFutures operatorSnapshotResult : operatorSnapshotsInProgress.values()) {
			if(operatorSnapshotResult != null) {
				try {
					operatorSnapshotResult.cancel();
				} catch(Exception e) {
					LOG.warn("Could not properly cancel an operator snapshot result.", e);
				}
			}
		}

		if(LOG.isDebugEnabled()) {
			LOG.debug("{} - did NOT finish synchronous part of checkpoint {}. Alignment duration: {} ms, snapshot duration {} ms", taskName,
				metadata.getCheckpointId(), metrics.getAlignmentDurationNanos() / 1_000_000, metrics.getSyncDurationMillis());
		}
	}

	/*************************************************
	 *
	 *  注释：
	 */
	private void prepareInflightDataSnapshot(long checkpointId) throws IOException {
		ResultPartitionWriter[] writers = env.getAllWriters();
		for(ResultPartitionWriter writer : writers) {
			for(int i = 0; i < writer.getNumberOfSubpartitions(); i++) {
				ResultSubpartition subpartition = writer.getSubpartition(i);
				channelStateWriter.addOutputData(checkpointId, subpartition.getSubpartitionInfo(), ChannelStateWriter.SEQUENCE_NUMBER_UNKNOWN,
					subpartition.requestInflightBufferSnapshot().toArray(new Buffer[0]));
			}
		}
		/*************************************************
		 *
		 *  注释：
		 */
		channelStateWriter.finishOutput(checkpointId);

		prepareInputSnapshot.apply(channelStateWriter, checkpointId).whenComplete((unused, ex) -> {
			if(ex != null) {
				channelStateWriter.abort(checkpointId, ex, false /* result is needed and cleaned by getWriteResult */);
			} else {
				channelStateWriter.finishInput(checkpointId);
			}
		});
	}

	private void finishAndReportAsync(Map<OperatorID, OperatorSnapshotFutures> snapshotFutures, CheckpointMetaData metadata,
		CheckpointMetrics metrics, CheckpointOptions options) {

		/*************************************************
		 *
		 *  注释： 提交一个 AsyncCheckpointRunnable 任务
		 */
		// we are transferring ownership over snapshotInProgressList for cleanup to the thread, active on submit
		executorService.execute(new AsyncCheckpointRunnable(snapshotFutures, metadata, metrics, System.nanoTime(), taskName, registerConsumer(),
			unregisterConsumer(), env, asyncExceptionHandler));
	}

	private Consumer<AsyncCheckpointRunnable> registerConsumer() {
		return asyncCheckpointRunnable -> {
			try {
				registerAsyncCheckpointRunnable(asyncCheckpointRunnable.getCheckpointId(), asyncCheckpointRunnable);
			} catch(IOException e) {
				throw new UncheckedIOException(e);
			}
		};
	}

	private Consumer<AsyncCheckpointRunnable> unregisterConsumer() {
		return asyncCheckpointRunnable -> unregisterAsyncCheckpointRunnable(asyncCheckpointRunnable.getCheckpointId());
	}

	private boolean takeSnapshotSync(Map<OperatorID, OperatorSnapshotFutures> operatorSnapshotsInProgress, CheckpointMetaData checkpointMetaData,
		CheckpointMetrics checkpointMetrics, CheckpointOptions checkpointOptions, OperatorChain<?, ?> operatorChain,
		Supplier<Boolean> isCanceled) throws Exception {

		/*************************************************
		 *
		 *  注释： 针对 StreamOperator 执行状态校验
		 */
		for(final StreamOperatorWrapper<?, ?> operatorWrapper : operatorChain.getAllOperators(true)) {
			if(operatorWrapper.isClosed()) {
				env.declineCheckpoint(checkpointMetaData.getCheckpointId(),
					new CheckpointException("Task Name" + taskName, CheckpointFailureReason.CHECKPOINT_DECLINED_TASK_CLOSING));
				return false;
			}
		}

		// TODO_MA 注释： 获取 checkpointID
		long checkpointId = checkpointMetaData.getCheckpointId();
		long started = System.nanoTime();

		ChannelStateWriteResult channelStateWriteResult = checkpointOptions.isUnalignedCheckpoint() ?
			// TODO_MA 注释： 默认： exactly once
			channelStateWriter.getAndRemoveWriteResult(checkpointId) :
			ChannelStateWriteResult.EMPTY;

		/*************************************************
		 *
		 *  注释： 获取 CheckpointStorage 相关信息
		 */
		CheckpointStreamFactory storage = checkpointStorage
			.resolveCheckpointStorageLocation(checkpointId, checkpointOptions.getTargetLocation());

		try {

			/*************************************************
			 *
			 *  注释： 调用每一个 StreamOperator 的 snapshotState 方法生成快照并存储到状态后端。
			 */
			for(StreamOperatorWrapper<?, ?> operatorWrapper : operatorChain.getAllOperators(true)) {
				if(!operatorWrapper.isClosed()) {
					operatorSnapshotsInProgress.put(operatorWrapper.getStreamOperator().getOperatorID(),

						/*************************************************
						 *
						 *  注释： 内部调用 StreamOperator 的 snapshotState 方法生成快照并存储到状态后端。
						 */
						buildOperatorSnapshotFutures(checkpointMetaData, checkpointOptions, operatorChain, operatorWrapper.getStreamOperator(),
							isCanceled, channelStateWriteResult, storage));
				}
			}
		} finally {
			checkpointStorage.clearCacheFor(checkpointId);
		}

		LOG.debug("{} - finished synchronous part of checkpoint {}. Alignment duration: {} ms, snapshot duration {} ms", taskName, checkpointId,
			checkpointMetrics.getAlignmentDurationNanos() / 1_000_000, checkpointMetrics.getSyncDurationMillis());

		checkpointMetrics.setSyncDurationMillis((System.nanoTime() - started) / 1_000_000);
		return true;
	}

	private OperatorSnapshotFutures buildOperatorSnapshotFutures(CheckpointMetaData checkpointMetaData, CheckpointOptions checkpointOptions,
		OperatorChain<?, ?> operatorChain, StreamOperator<?> op, Supplier<Boolean> isCanceled, ChannelStateWriteResult channelStateWriteResult,
		CheckpointStreamFactory storage) throws Exception {

		/*************************************************
		 *
		 *  注释： 调用 StreaOperator 的 snapshotState 方法生成快照并存储到状态后端。
		 */
		OperatorSnapshotFutures snapshotInProgress = checkpointStreamOperator(op, checkpointMetaData, checkpointOptions, storage, isCanceled);

		if(op == operatorChain.getHeadOperator()) {
			snapshotInProgress.setInputChannelStateFuture(
				channelStateWriteResult.getInputChannelStateHandles().thenApply(StateObjectCollection::new).thenApply(SnapshotResult::of));
		}
		if(op == operatorChain.getTailOperator()) {
			snapshotInProgress.setResultSubpartitionStateFuture(
				channelStateWriteResult.getResultSubpartitionStateHandles().thenApply(StateObjectCollection::new).thenApply(SnapshotResult::of));
		}
		return snapshotInProgress;
	}

	private Set<Long> createAbortedCheckpointSetWithLimitSize(int maxRecordAbortedCheckpoints) {
		return Collections.newSetFromMap(new LinkedHashMap<Long, Boolean>() {
			private static final long serialVersionUID = 1L;

			@Override
			protected boolean removeEldestEntry(Map.Entry<Long, Boolean> eldest) {
				return size() > maxRecordAbortedCheckpoints;
			}
		});
	}

	// Caches checkpoint output stream factories to prevent multiple output stream per checkpoint.
	// This could result from requesting output stream by different entities (this and channelStateWriter)
	// We can't just pass a stream to the channelStateWriter because it can receive checkpoint call earlier than this class
	// in some unaligned checkpoints scenarios
	private static class CachingCheckpointStorageWorkerView implements CheckpointStorageWorkerView {
		private final Map<Long, CheckpointStreamFactory> cache = new ConcurrentHashMap<>();
		private final CheckpointStorageWorkerView delegate;

		private CachingCheckpointStorageWorkerView(CheckpointStorageWorkerView delegate) {
			this.delegate = delegate;
		}

		void clearCacheFor(long checkpointId) {
			cache.remove(checkpointId);
		}

		@Override
		public CheckpointStreamFactory resolveCheckpointStorageLocation(long checkpointId, CheckpointStorageLocationReference reference) {
			return cache.computeIfAbsent(checkpointId, id -> {
				try {
					/*************************************************
					 *
					 *  注释： 获取 Checkpoint StorageLocation
					 */
					return delegate.resolveCheckpointStorageLocation(checkpointId, reference);
				} catch(IOException e) {
					throw new FlinkRuntimeException(e);
				}
			});
		}

		@Override
		public CheckpointStreamFactory.CheckpointStateOutputStream createTaskOwnedStateStream() throws IOException {
			return delegate.createTaskOwnedStateStream();
		}
	}

	private static OperatorSnapshotFutures checkpointStreamOperator(StreamOperator<?> op, CheckpointMetaData checkpointMetaData,
		CheckpointOptions checkpointOptions, CheckpointStreamFactory storageLocation, Supplier<Boolean> isCanceled) throws Exception {
		try {

			/*************************************************
			 *
			 *  注释： 调用 StreaOperator 的 snapshotState 方法生成快照并存储到状态后端。
			 */
			return op.snapshotState(checkpointMetaData.getCheckpointId(), checkpointMetaData.getTimestamp(), checkpointOptions, storageLocation);
		} catch(Exception ex) {
			if(!isCanceled.get()) {
				LOG.info(ex.getMessage(), ex);
			}
			throw ex;
		}
	}
}
