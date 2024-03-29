/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.flink.streaming.api.operators;

import org.apache.flink.annotation.Experimental;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.api.java.functions.KeySelector;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.metrics.MetricGroup;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.execution.Environment;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.metrics.groups.OperatorMetricGroup;
import org.apache.flink.runtime.metrics.groups.TaskManagerJobMetricGroup;
import org.apache.flink.runtime.metrics.groups.UnregisteredMetricGroups;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.VoidNamespace;
import org.apache.flink.runtime.state.VoidNamespaceSerializer;
import org.apache.flink.streaming.api.graph.StreamConfig;
import org.apache.flink.streaming.api.operators.StreamOperatorStateHandler.CheckpointedStreamOperator;
import org.apache.flink.streaming.api.watermark.Watermark;
import org.apache.flink.streaming.runtime.streamrecord.LatencyMarker;
import org.apache.flink.streaming.runtime.streamrecord.StreamRecord;
import org.apache.flink.streaming.runtime.tasks.ProcessingTimeService;
import org.apache.flink.streaming.runtime.tasks.StreamTask;
import org.apache.flink.streaming.util.LatencyStats;
import org.apache.flink.util.Preconditions;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * // TODO_MA 注释： 所有流运算符的新基类，旨在最终替代{@link AbstractStreamOperator}。
 * // TODO_MA 注释： 当前旨在仅与{@link MultipleInputStreamOperator}一起正常工作。
 * New base class for all stream operators, intended to eventually replace {@link AbstractStreamOperator}.
 * Currently intended to work smoothly just with {@link MultipleInputStreamOperator}.
 *
 * // TODO_MA 注释： 与{@link AbstractStreamOperator}相比，一个值得注意的区别是缺少
 * // TODO_MA 注释： {@link AbstractStreamOperator＃setup（StreamTask，StreamConfig，Output）}
 * // TODO_MA 注释： 有利于构造函数中的初始化*，并消除了与{@link StreamTask}。等类的紧密耦合
 * <p>One note-able difference in comparison to {@link AbstractStreamOperator} is lack of
 * {@link AbstractStreamOperator#setup(StreamTask, StreamConfig, Output)} in favor of initialisation
 * in the constructor, and removed some tight coupling with classes like {@link StreamTask}.
 *
 * // TODO_MA 注释： 保证方法不会被同时调用。
 * <p>Methods are guaranteed not to be called concurrently.
 *
 * @param <OUT> The output type of the operator
 */
@Experimental
public abstract class AbstractStreamOperatorV2<OUT> implements StreamOperator<OUT>, CheckpointedStreamOperator {
	/**
	 * The logger used by the operator class and its subclasses.
	 */
	protected static final Logger LOG = LoggerFactory.getLogger(AbstractStreamOperatorV2.class);

	protected final StreamConfig config;
	protected final Output<StreamRecord<OUT>> output;
	private final StreamingRuntimeContext runtimeContext;
	private final ExecutionConfig executionConfig;
	private final ClassLoader userCodeClassLoader;
	private final CloseableRegistry cancelables;
	private final long[] inputWatermarks;

	/**
	 * Metric group for the operator.
	 */
	protected final OperatorMetricGroup metrics;
	protected final LatencyStats latencyStats;
	protected final ProcessingTimeService processingTimeService;

	private StreamOperatorStateHandler stateHandler;
	private InternalTimeServiceManager<?> timeServiceManager;

	// We keep track of watermarks from both inputs, the combined input is the minimum
	// Once the minimum advances we emit a new watermark for downstream operators
	private long combinedWatermark = Long.MIN_VALUE;

	public AbstractStreamOperatorV2(StreamOperatorParameters<OUT> parameters, int numberOfInputs) {
		inputWatermarks = new long[numberOfInputs];
		Arrays.fill(inputWatermarks, Long.MIN_VALUE);
		final Environment environment = parameters.getContainingTask().getEnvironment();
		config = parameters.getStreamConfig();
		CountingOutput<OUT> countingOutput;
		OperatorMetricGroup operatorMetricGroup;
		try {
			operatorMetricGroup = environment.getMetricGroup().getOrAddOperator(config.getOperatorID(), config.getOperatorName());
			countingOutput = new CountingOutput(parameters.getOutput(), operatorMetricGroup.getIOMetricGroup().getNumRecordsOutCounter());
			if(config.isChainStart()) {
				operatorMetricGroup.getIOMetricGroup().reuseInputMetricsForTask();
			}
			if(config.isChainEnd()) {
				operatorMetricGroup.getIOMetricGroup().reuseOutputMetricsForTask();
			}
		} catch(Exception e) {
			LOG.warn("An error occurred while instantiating task metrics.", e);
			countingOutput = null;
			operatorMetricGroup = null;
		}

		if(countingOutput == null || operatorMetricGroup == null) {
			metrics = UnregisteredMetricGroups.createUnregisteredOperatorMetricGroup();
			output = parameters.getOutput();
		} else {
			metrics = operatorMetricGroup;
			output = countingOutput;
		}

		latencyStats = createLatencyStats(environment.getTaskManagerInfo().getConfiguration(),
			parameters.getContainingTask().getIndexInSubtaskGroup());

		processingTimeService = Preconditions.checkNotNull(parameters.getProcessingTimeService());
		executionConfig = parameters.getContainingTask().getExecutionConfig();
		userCodeClassLoader = parameters.getContainingTask().getUserCodeClassLoader();
		cancelables = parameters.getContainingTask().getCancelables();

		runtimeContext = new StreamingRuntimeContext(environment, environment.getAccumulatorRegistry().getUserMap(), operatorMetricGroup,
			getOperatorID(), processingTimeService, null, environment.getExternalResourceInfoProvider());
	}

	private LatencyStats createLatencyStats(Configuration taskManagerConfig, int indexInSubtaskGroup) {
		try {
			int historySize = taskManagerConfig.getInteger(MetricOptions.LATENCY_HISTORY_SIZE);
			if(historySize <= 0) {
				LOG.warn("{} has been set to a value equal or below 0: {}. Using default.", MetricOptions.LATENCY_HISTORY_SIZE, historySize);
				historySize = MetricOptions.LATENCY_HISTORY_SIZE.defaultValue();
			}

			final String configuredGranularity = taskManagerConfig.getString(MetricOptions.LATENCY_SOURCE_GRANULARITY);
			LatencyStats.Granularity granularity;
			try {
				granularity = LatencyStats.Granularity.valueOf(configuredGranularity.toUpperCase(Locale.ROOT));
			} catch(IllegalArgumentException iae) {
				granularity = LatencyStats.Granularity.OPERATOR;
				LOG.warn("Configured value {} option for {} is invalid. Defaulting to {}.", configuredGranularity,
					MetricOptions.LATENCY_SOURCE_GRANULARITY.key(), granularity);
			}
			TaskManagerJobMetricGroup jobMetricGroup = this.metrics.parent().parent();
			return new LatencyStats(jobMetricGroup.addGroup("latency"), historySize, indexInSubtaskGroup, getOperatorID(), granularity);
		} catch(Exception e) {
			LOG.warn("An error occurred while instantiating latency metrics.", e);
			return new LatencyStats(UnregisteredMetricGroups.createUnregisteredTaskManagerJobMetricGroup().addGroup("latency"), 1, 0,
				new OperatorID(), LatencyStats.Granularity.SINGLE);
		}
	}

	@Override
	public MetricGroup getMetricGroup() {
		return metrics;
	}

	@Override
	public final void initializeState(StreamTaskStateInitializer streamTaskStateManager) throws Exception {
		final TypeSerializer<?> keySerializer = config.getStateKeySerializer(getUserCodeClassloader());

		// TODO_MA 注释： 获取 StreamOperatorStateContext
		final StreamOperatorStateContext context = streamTaskStateManager
			.streamOperatorStateContext(getOperatorID(), getClass().getSimpleName(), getProcessingTimeService(), this, keySerializer,
				cancelables, metrics);

		/*************************************************
		 *
		 *  注释： 创建 StateHandler
		 */
		stateHandler = new StreamOperatorStateHandler(context, getExecutionConfig(), cancelables);
		timeServiceManager = context.internalTimerServiceManager();

		/*************************************************
		 *
		 *  注释： 初始化状态
		 */
		stateHandler.initializeOperatorState(this);
	}

	/**
	 * This method is called immediately before any elements are processed, it should contain the
	 * operator's initialization logic, e.g. state initialization.
	 *
	 * <p>The default implementation does nothing.
	 *
	 * @throws Exception An exception in this method causes the operator to fail.
	 */
	@Override
	public void open() throws Exception {
	}

	/**
	 * This method is called after all records have been added to the operators via the methods
	 * {@link OneInputStreamOperator#processElement(StreamRecord)}, or
	 * {@link TwoInputStreamOperator#processElement1(StreamRecord)} and
	 * {@link TwoInputStreamOperator#processElement2(StreamRecord)}.
	 *
	 * <p>The method is expected to flush all remaining buffered data. Exceptions during this flushing
	 * of buffered should be propagated, in order to cause the operation to be recognized asa failed,
	 * because the last data items are not processed properly.
	 *
	 * @throws Exception An exception in this method causes the operator to fail.
	 */
	@Override
	public void close() throws Exception {
	}

	/**
	 * This method is called at the very end of the operator's life, both in the case of a successful
	 * completion of the operation, and in the case of a failure and canceling.
	 *
	 * <p>This method is expected to make a thorough effort to release all resources
	 * that the operator has acquired.
	 */
	@Override
	public void dispose() throws Exception {
		if(stateHandler != null) {
			stateHandler.dispose();
		}
	}

	@Override
	public void prepareSnapshotPreBarrier(long checkpointId) throws Exception {
		// the default implementation does nothing and accepts the checkpoint
		// this is purely for subclasses to override
	}

	@Override
	public final OperatorSnapshotFutures snapshotState(long checkpointId, long timestamp, CheckpointOptions checkpointOptions,
		CheckpointStreamFactory factory) throws Exception {

		/*************************************************
		 *
		 *  注释：
		 */
		return stateHandler
			.snapshotState(this, Optional.ofNullable(timeServiceManager), getOperatorName(), checkpointId, timestamp, checkpointOptions,
				factory);
	}

	/**
	 * Stream operators with state, which want to participate in a snapshot need to override this hook method.
	 *
	 * @param context context that provides information and means required for taking a snapshot
	 */
	@Override
	public void snapshotState(StateSnapshotContext context) throws Exception {
	}

	/**
	 * Stream operators with state which can be restored need to override this hook method.
	 *
	 * @param context context that allows to register different states.
	 */
	@Override
	public void initializeState(StateInitializationContext context) throws Exception {
	}

	@Override
	public void notifyCheckpointComplete(long checkpointId) throws Exception {
		stateHandler.notifyCheckpointComplete(checkpointId);
	}

	@Override
	public void notifyCheckpointAborted(long checkpointId) throws Exception {
		stateHandler.notifyCheckpointAborted(checkpointId);
	}

	// ------------------------------------------------------------------------
	//  Properties and Services
	// ------------------------------------------------------------------------

	/**
	 * Gets the execution config defined on the execution environment of the job to which this
	 * operator belongs.
	 *
	 * @return The job's execution config.
	 */
	public ExecutionConfig getExecutionConfig() {
		return executionConfig;
	}

	public StreamConfig getOperatorConfig() {
		return config;
	}

	public ClassLoader getUserCodeClassloader() {
		return userCodeClassLoader;
	}

	/**
	 * Return the operator name. If the runtime context has been set, then the task name with
	 * subtask index is returned. Otherwise, the simple class name is returned.
	 *
	 * @return If runtime context is set, then return task name with subtask index. Otherwise return
	 * simple class name.
	 */
	protected String getOperatorName() {
		if(runtimeContext != null) {
			return runtimeContext.getTaskNameWithSubtasks();
		} else {
			return getClass().getSimpleName();
		}
	}

	/**
	 * Returns a context that allows the operator to query information about the execution and also
	 * to interact with systems such as broadcast variables and managed state. This also allows
	 * to register timers.
	 */
	public StreamingRuntimeContext getRuntimeContext() {
		return runtimeContext;
	}

	@SuppressWarnings("unchecked")
	@VisibleForTesting
	public <K> KeyedStateBackend<K> getKeyedStateBackend() {
		return (KeyedStateBackend<K>) stateHandler.getKeyedStateBackend();
	}

	@VisibleForTesting
	public OperatorStateBackend getOperatorStateBackend() {
		return stateHandler.getOperatorStateBackend();
	}

	/**
	 * Returns the {@link ProcessingTimeService} responsible for getting the current
	 * processing time and registering timers.
	 */
	@VisibleForTesting
	public ProcessingTimeService getProcessingTimeService() {
		return processingTimeService;
	}

	/**
	 * Creates a partitioned state handle, using the state backend configured for this task.
	 *
	 * @throws IllegalStateException Thrown, if the key/value state was already initialized.
	 * @throws Exception             Thrown, if the state backend cannot create the key/value state.
	 */
	protected <S extends State> S getPartitionedState(StateDescriptor<S, ?> stateDescriptor) throws Exception {
		return getPartitionedState(VoidNamespace.INSTANCE, VoidNamespaceSerializer.INSTANCE, stateDescriptor);
	}

	protected <N, S extends State, T> S getOrCreateKeyedState(TypeSerializer<N> namespaceSerializer,
		StateDescriptor<S, T> stateDescriptor) throws Exception {
		return stateHandler.getOrCreateKeyedState(namespaceSerializer, stateDescriptor);
	}

	/**
	 * Creates a partitioned state handle, using the state backend configured for this task.
	 *
	 * @throws IllegalStateException Thrown, if the key/value state was already initialized.
	 * @throws Exception             Thrown, if the state backend cannot create the key/value state.
	 */
	protected <S extends State, N> S getPartitionedState(N namespace, TypeSerializer<N> namespaceSerializer,
		StateDescriptor<S, ?> stateDescriptor) throws Exception {
		return stateHandler.getPartitionedState(namespace, namespaceSerializer, stateDescriptor);
	}

	protected <T> void internalSetKeyContextElement(StreamRecord<T> record, KeySelector<T, ?> selector) throws Exception {
		if(selector != null) {
			Object key = selector.getKey(record.getValue());
			setCurrentKey(key);
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public void setCurrentKey(Object key) {
		stateHandler.setCurrentKey(key);
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public Object getCurrentKey() {
		return stateHandler.getCurrentKey();
	}

	public Optional<KeyedStateStore> getKeyedStateStore() {
		if(stateHandler == null) {
			return Optional.empty();
		}
		return stateHandler.getKeyedStateStore();
	}

	protected void reportOrForwardLatencyMarker(LatencyMarker marker) {
		// all operators are tracking latencies
		this.latencyStats.reportLatency(marker);

		// everything except sinks forwards latency markers
		this.output.emitLatencyMarker(marker);
	}

	// ------------------------------------------------------------------------
	//  Watermark handling
	// ------------------------------------------------------------------------

	/**
	 * Returns a {@link InternalTimerService} that can be used to query current processing time
	 * and event time and to set timers. An operator can have several timer services, where
	 * each has its own namespace serializer. Timer services are differentiated by the string
	 * key that is given when requesting them, if you call this method with the same key
	 * multiple times you will get the same timer service instance in subsequent requests.
	 *
	 * <p>Timers are always scoped to a key, the currently active key of a keyed stream operation.
	 * When a timer fires, this key will also be set as the currently active key.
	 *
	 * <p>Each timer has attached metadata, the namespace. Different timer services
	 * can have a different namespace type. If you don't need namespace differentiation you
	 * can use {@link VoidNamespaceSerializer} as the namespace serializer.
	 *
	 * @param name                The name of the requested timer service. If no service exists under the given
	 *                            name a new one will be created and returned.
	 * @param namespaceSerializer {@code TypeSerializer} for the timer namespace.
	 * @param triggerable         The {@link Triggerable} that should be invoked when timers fire
	 * @param <N>                 The type of the timer namespace.
	 */
	@VisibleForTesting
	public <K, N> InternalTimerService<N> getInternalTimerService(String name, TypeSerializer<N> namespaceSerializer,
		Triggerable<K, N> triggerable) {
		if(timeServiceManager == null) {
			throw new RuntimeException("The timer service has not been initialized.");
		}
		InternalTimeServiceManager<K> keyedTimeServiceHandler = (InternalTimeServiceManager<K>) timeServiceManager;
		return keyedTimeServiceHandler.getInternalTimerService(name, namespaceSerializer, triggerable, stateHandler.getKeyedStateBackend());
	}

	public void processWatermark(Watermark mark) throws Exception {
		if(timeServiceManager != null) {
			timeServiceManager.advanceWatermark(mark);
		}
		output.emitWatermark(mark);
	}

	protected void reportWatermark(Watermark mark, int inputId) throws Exception {
		inputWatermarks[inputId - 1] = mark.getTimestamp();
		long newMin = mark.getTimestamp();
		for(long inputWatermark : inputWatermarks) {
			newMin = Math.min(inputWatermark, newMin);
		}
		if(newMin > combinedWatermark) {
			combinedWatermark = newMin;
			processWatermark(new Watermark(combinedWatermark));
		}
	}

	@Override
	public OperatorID getOperatorID() {
		return config.getOperatorID();
	}

	@VisibleForTesting
	public int numProcessingTimeTimers() {
		return timeServiceManager == null ? 0 : timeServiceManager.numProcessingTimeTimers();
	}

	@VisibleForTesting
	public int numEventTimeTimers() {
		return timeServiceManager == null ? 0 : timeServiceManager.numEventTimeTimers();
	}

	@Override
	public void setKeyContextElement1(StreamRecord<?> record) throws Exception {
		throw new IllegalStateException("This method should never be called. Use Input class instead");
	}

	@Override
	public void setKeyContextElement2(StreamRecord<?> record) throws Exception {
		throw new IllegalStateException("This method should never be called. Use Input class instead");
	}

	protected Optional<InternalTimeServiceManager<?>> getTimeServiceManager() {
		return Optional.ofNullable(timeServiceManager);
	}
}
