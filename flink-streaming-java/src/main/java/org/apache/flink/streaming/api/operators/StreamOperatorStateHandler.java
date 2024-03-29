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

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.ExecutionConfig;
import org.apache.flink.api.common.state.KeyedStateStore;
import org.apache.flink.api.common.state.State;
import org.apache.flink.api.common.state.StateDescriptor;
import org.apache.flink.api.common.typeutils.TypeSerializer;
import org.apache.flink.core.fs.CloseableRegistry;
import org.apache.flink.runtime.checkpoint.CheckpointException;
import org.apache.flink.runtime.checkpoint.CheckpointFailureReason;
import org.apache.flink.runtime.checkpoint.CheckpointOptions;
import org.apache.flink.runtime.state.AbstractKeyedStateBackend;
import org.apache.flink.runtime.state.CheckpointStreamFactory;
import org.apache.flink.runtime.state.DefaultKeyedStateStore;
import org.apache.flink.runtime.state.KeyGroupRange;
import org.apache.flink.runtime.state.KeyGroupStatePartitionStreamProvider;
import org.apache.flink.runtime.state.KeyedStateBackend;
import org.apache.flink.runtime.state.OperatorStateBackend;
import org.apache.flink.runtime.state.StateInitializationContext;
import org.apache.flink.runtime.state.StateInitializationContextImpl;
import org.apache.flink.runtime.state.StatePartitionStreamProvider;
import org.apache.flink.runtime.state.StateSnapshotContext;
import org.apache.flink.runtime.state.StateSnapshotContextSynchronousImpl;
import org.apache.flink.util.CloseableIterable;
import org.apache.flink.util.IOUtils;

import org.apache.flink.shaded.guava18.com.google.common.io.Closer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nullable;

import java.io.Closeable;
import java.io.IOException;
import java.util.Optional;

import static org.apache.flink.util.Preconditions.checkState;

/**
 * Class encapsulating various state backend handling logic for {@link StreamOperator} implementations.
 */
@Internal
public class StreamOperatorStateHandler {

	protected static final Logger LOG = LoggerFactory.getLogger(StreamOperatorStateHandler.class);

	/**
	 * Backend for keyed state. This might be empty if we're not on a keyed stream.
	 */
	@Nullable
	private final AbstractKeyedStateBackend<?> keyedStateBackend;
	private final CloseableRegistry closeableRegistry;
	@Nullable
	private final DefaultKeyedStateStore keyedStateStore;
	private final OperatorStateBackend operatorStateBackend;
	private final StreamOperatorStateContext context;

	public StreamOperatorStateHandler(StreamOperatorStateContext context, ExecutionConfig executionConfig, CloseableRegistry closeableRegistry) {
		this.context = context;

		// TODO_MA 注释： 从 AbstractStreamOperator 传递到 StreamOperatorStateHandler
		operatorStateBackend = context.operatorStateBackend();

		// TODO_MA 注释： 从 AbstractStreamOperator 传递到 StreamOperatorStateHandler
		keyedStateBackend = context.keyedStateBackend();

		this.closeableRegistry = closeableRegistry;

		// TODO_MA 注释： keyedStateStore = DefaultKeyedStateStore
		if(keyedStateBackend != null) {
			keyedStateStore = new DefaultKeyedStateStore(keyedStateBackend, executionConfig);
		} else {
			keyedStateStore = null;
		}
	}

	/*************************************************
	 *
	 *  注释： 初始化 Ooperator 的 state
	 */
	public void initializeOperatorState(CheckpointedStreamOperator streamOperator) throws Exception {

		CloseableIterable<KeyGroupStatePartitionStreamProvider> keyedStateInputs = context.rawKeyedStateInputs();
		CloseableIterable<StatePartitionStreamProvider> operatorStateInputs = context.rawOperatorStateInputs();

		try {

			/*************************************************
			 *
			 *  注释： 初始化得到 ： StateInitializationContextImpl
			 */
			StateInitializationContext initializationContext = new StateInitializationContextImpl(context.isRestored(),
				// information whether we restore or start for the first time
				operatorStateBackend, // access to operator state backend
				keyedStateStore, // access to keyed state backend
				keyedStateInputs, // access to keyed state stream
				operatorStateInputs); // access to operator state stream

			/*************************************************
			 *
			 *  注释： 初始化状态！
			 */
			streamOperator.initializeState(initializationContext);
		} finally {
			closeFromRegistry(operatorStateInputs, closeableRegistry);
			closeFromRegistry(keyedStateInputs, closeableRegistry);
		}
	}

	private static void closeFromRegistry(Closeable closeable, CloseableRegistry registry) {
		if(registry.unregisterCloseable(closeable)) {
			IOUtils.closeQuietly(closeable);
		}
	}

	public void dispose() throws Exception {
		try(Closer closer = Closer.create()) {
			if(closeableRegistry.unregisterCloseable(operatorStateBackend)) {
				closer.register(operatorStateBackend);
			}
			if(closeableRegistry.unregisterCloseable(keyedStateBackend)) {
				closer.register(keyedStateBackend);
			}
			if(operatorStateBackend != null) {
				closer.register(() -> operatorStateBackend.dispose());
			}
			if(keyedStateBackend != null) {
				closer.register(() -> keyedStateBackend.dispose());
			}
		}
	}

	public OperatorSnapshotFutures snapshotState(CheckpointedStreamOperator streamOperator,
		Optional<InternalTimeServiceManager<?>> timeServiceManager, String operatorName, long checkpointId, long timestamp,
		CheckpointOptions checkpointOptions, CheckpointStreamFactory factory) throws CheckpointException {
		KeyGroupRange keyGroupRange = null != keyedStateBackend ? keyedStateBackend.getKeyGroupRange() : KeyGroupRange.EMPTY_KEY_GROUP_RANGE;

		OperatorSnapshotFutures snapshotInProgress = new OperatorSnapshotFutures();

		/*************************************************
		 *
		 *  注释： 初始化 StateSnapshotContextSynchronousImpl
		 */
		StateSnapshotContextSynchronousImpl snapshotContext = new StateSnapshotContextSynchronousImpl(checkpointId, timestamp, factory,
			keyGroupRange, closeableRegistry);

		/*************************************************
		 *
		 *  注释： 调用虫子啊方法
		 */
		snapshotState(streamOperator, timeServiceManager, operatorName, checkpointId, timestamp, checkpointOptions, factory, snapshotInProgress,
			snapshotContext);

		return snapshotInProgress;
	}

	@VisibleForTesting
	void snapshotState(CheckpointedStreamOperator streamOperator, Optional<InternalTimeServiceManager<?>> timeServiceManager,
		String operatorName, long checkpointId, long timestamp, CheckpointOptions checkpointOptions, CheckpointStreamFactory factory,
		OperatorSnapshotFutures snapshotInProgress, StateSnapshotContextSynchronousImpl snapshotContext) throws CheckpointException {
		try {
			if(timeServiceManager.isPresent()) {
				checkState(keyedStateBackend != null, "keyedStateBackend should be available with timeServiceManager");
				timeServiceManager.get().snapshotState(keyedStateBackend, snapshotContext, operatorName);
			}

			/*************************************************
			 *
			 *  注释： 将 该 StreamOperator 的状态更新到 StateTable
			 *  1、SourceOperator
			 *  2、AbstractUdfStreamOperator
			 */
			streamOperator.snapshotState(snapshotContext);

			snapshotInProgress.setKeyedStateRawFuture(snapshotContext.getKeyedStateStreamFuture());
			snapshotInProgress.setOperatorStateRawFuture(snapshotContext.getOperatorStateStreamFuture());

			/*************************************************
			 *
			 *  注释： 如果 operatorStateBackend， 则执行 operatorStateBackend 的 snapshot 动作
			 */
			if(null != operatorStateBackend) {
				snapshotInProgress
					.setOperatorStateManagedFuture(operatorStateBackend.snapshot(checkpointId, timestamp, factory, checkpointOptions));
			}

			/*************************************************
			 *
			 *  注释： 如果 keyedStateBackend， 则执行 keyedStateBackend 的 snapshot 动作
			 *  keyedStateBackend = HeapKeyedStateBackend
			 */
			if(null != keyedStateBackend) {
				snapshotInProgress.setKeyedStateManagedFuture(keyedStateBackend.snapshot(checkpointId, timestamp, factory, checkpointOptions));
			}
		} catch(Exception snapshotException) {
			try {
				snapshotInProgress.cancel();
			} catch(Exception e) {
				snapshotException.addSuppressed(e);
			}

			String snapshotFailMessage = "Could not complete snapshot " + checkpointId + " for operator " + operatorName + ".";

			try {
				snapshotContext.closeExceptionally();
			} catch(IOException e) {
				snapshotException.addSuppressed(e);
			}
			throw new CheckpointException(snapshotFailMessage, CheckpointFailureReason.CHECKPOINT_DECLINED, snapshotException);
		}
	}

	public void notifyCheckpointComplete(long checkpointId) throws Exception {
		if(keyedStateBackend != null) {
			keyedStateBackend.notifyCheckpointComplete(checkpointId);
		}
	}

	public void notifyCheckpointAborted(long checkpointId) throws Exception {
		if(keyedStateBackend != null) {
			keyedStateBackend.notifyCheckpointAborted(checkpointId);
		}
	}

	@SuppressWarnings("unchecked")
	public <K> KeyedStateBackend<K> getKeyedStateBackend() {
		return (KeyedStateBackend<K>) keyedStateBackend;
	}

	public OperatorStateBackend getOperatorStateBackend() {
		return operatorStateBackend;
	}

	public <N, S extends State, T> S getOrCreateKeyedState(TypeSerializer<N> namespaceSerializer,
		StateDescriptor<S, T> stateDescriptor) throws Exception {

		if(keyedStateStore != null) {
			return keyedStateBackend.getOrCreateKeyedState(namespaceSerializer, stateDescriptor);
		} else {
			throw new IllegalStateException(
				"Cannot create partitioned state. " + "The keyed state backend has not been set." + "This indicates that the operator is not partitioned/keyed.");
		}
	}

	/**
	 * Creates a partitioned state handle, using the state backend configured for this task.
	 *
	 * @throws IllegalStateException Thrown, if the key/value state was already initialized.
	 * @throws Exception             Thrown, if the state backend cannot create the key/value state.
	 */
	protected <S extends State, N> S getPartitionedState(N namespace, TypeSerializer<N> namespaceSerializer,
		StateDescriptor<S, ?> stateDescriptor) throws Exception {

		/*
	    TODO: NOTE: This method does a lot of work caching / retrieving states just to update the namespace.
	    This method should be removed for the sake of namespaces being lazily fetched from the keyed
	    state backend, or being set on the state directly.
	    */

		if(keyedStateStore != null) {
			return keyedStateBackend.getPartitionedState(namespace, namespaceSerializer, stateDescriptor);
		} else {
			throw new RuntimeException(
				"Cannot create partitioned state. The keyed state " + "backend has not been set. This indicates that the operator is not " + "partitioned/keyed.");
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public void setCurrentKey(Object key) {
		if(keyedStateBackend != null) {
			try {
				// need to work around type restrictions
				@SuppressWarnings("unchecked,rawtypes") AbstractKeyedStateBackend rawBackend = (AbstractKeyedStateBackend) keyedStateBackend;

				rawBackend.setCurrentKey(key);
			} catch(Exception e) {
				throw new RuntimeException("Exception occurred while setting the current key context.", e);
			}
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	public Object getCurrentKey() {
		if(keyedStateBackend != null) {
			return keyedStateBackend.getCurrentKey();
		} else {
			throw new UnsupportedOperationException("Key can only be retrieved on KeyedStream.");
		}
	}

	public Optional<KeyedStateStore> getKeyedStateStore() {
		return Optional.ofNullable(keyedStateStore);
	}

	/**
	 * Custom state handling hooks to be invoked by {@link StreamOperatorStateHandler}.
	 */
	public interface CheckpointedStreamOperator {
		void initializeState(StateInitializationContext context) throws Exception;

		void snapshotState(StateSnapshotContext context) throws Exception;
	}
}
