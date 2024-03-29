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

package org.apache.flink.runtime.io.network.partition.consumer;

import org.apache.flink.runtime.checkpoint.channel.ChannelStateReader;
import org.apache.flink.runtime.event.TaskEvent;
import org.apache.flink.runtime.io.network.api.EndOfPartitionEvent;
import org.apache.flink.runtime.io.network.buffer.BufferReceivedListener;

import org.apache.flink.shaded.guava18.com.google.common.collect.Sets;

import java.io.IOException;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkArgument;
import static org.apache.flink.util.Preconditions.checkNotNull;
import static org.apache.flink.util.Preconditions.checkState;

/**
 * Input gate wrapper to union the input from multiple input gates.
 *
 * <p>Each input gate has input channels attached from which it reads data. At each input gate, the
 * input channels have unique IDs from 0 (inclusive) to the number of input channels (exclusive).
 *
 * <pre>
 * +---+---+      +---+---+---+
 * | 0 | 1 |      | 0 | 1 | 2 |
 * +--------------+--------------+
 * | Input gate 0 | Input gate 1 |
 * +--------------+--------------+
 * </pre>
 *
 * <p>The union input gate maps these IDs from 0 to the *total* number of input channels across all
 * unioned input gates, e.g. the channels of input gate 0 keep their original indexes and the
 * channel indexes of input gate 1 are set off by 2 to 2--4.
 *
 * <pre>
 * +---+---++---+---+---+
 * | 0 | 1 || 2 | 3 | 4 |
 * +--------------------+
 * | Union input gate   |
 * +--------------------+
 * </pre>
 *
 * <strong>It is NOT possible to recursively union union input gates.</strong>
 */
public class UnionInputGate extends InputGate {

	/**
	 * The input gates to union.
	 */
	private final Map<Integer, InputGate> inputGatesByGateIndex;

	/*************************************************
	 *
	 *  注释：
	 */
	private final Set<IndexedInputGate> inputGatesWithRemainingData;

	/**
	 * // TODO_MA 注释： UnionInputGate 时多个 SingleInputGate 联合组成，它的内部有一个 inputGatesWithData 队列
	 * Gates, which notified this input gate about available data. We are using it as a FIFO
	 * queue of {@link InputGate}s to avoid starvation and provide some basic fairness.
	 */
	private final LinkedHashSet<IndexedInputGate> inputGatesWithData = new LinkedHashSet<>();

	private final int[] inputChannelToInputGateIndex;

	/**
	 * A mapping from input gate index to (logical) channel index offset. Valid channel indexes go from 0
	 * (inclusive) to the total number of input channels (exclusive).
	 */
	private final int[] inputGateChannelIndexOffsets;

	public UnionInputGate(IndexedInputGate... inputGates) {
		inputGatesByGateIndex = Arrays.stream(inputGates).collect(Collectors.toMap(IndexedInputGate::getGateIndex, ig -> ig));
		checkArgument(inputGates.length > 1, "Union input gate should union at least two input gates.");

		if(Arrays.stream(inputGates).map(IndexedInputGate::getGateIndex).distinct().count() != inputGates.length) {
			throw new IllegalArgumentException("Union of two input gates with the same gate index. Given indices: " + Arrays.stream(inputGates)
				.map(IndexedInputGate::getGateIndex).collect(Collectors.toList()));
		}

		this.inputGatesWithRemainingData = Sets.newHashSetWithExpectedSize(inputGates.length);

		final int maxGateIndex = Arrays.stream(inputGates).mapToInt(IndexedInputGate::getGateIndex).max().orElse(0);
		int totalNumberOfInputChannels = Arrays.stream(inputGates).mapToInt(IndexedInputGate::getNumberOfInputChannels).sum();

		inputGateChannelIndexOffsets = new int[maxGateIndex + 1];
		inputChannelToInputGateIndex = new int[totalNumberOfInputChannels];

		int currentNumberOfInputChannels = 0;
		for(final IndexedInputGate inputGate : inputGates) {
			inputGateChannelIndexOffsets[inputGate.getGateIndex()] = currentNumberOfInputChannels;
			int previousNumberOfInputChannels = currentNumberOfInputChannels;
			currentNumberOfInputChannels += inputGate.getNumberOfInputChannels();
			Arrays.fill(inputChannelToInputGateIndex, previousNumberOfInputChannels, currentNumberOfInputChannels, inputGate.getGateIndex());
		}

		synchronized(inputGatesWithData) {
			for(IndexedInputGate inputGate : inputGates) {
				inputGatesWithRemainingData.add(inputGate);

				CompletableFuture<?> available = inputGate.getAvailableFuture();

				if(available.isDone()) {
					inputGatesWithData.add(inputGate);
				} else {
					available.thenRun(() -> queueInputGate(inputGate));
				}
			}

			if(!inputGatesWithData.isEmpty()) {
				availabilityHelper.resetAvailable();
			}
		}
	}

	/**
	 * Returns the total number of input channels across all unioned input gates.
	 */
	@Override
	public int getNumberOfInputChannels() {
		return inputChannelToInputGateIndex.length;
	}

	@Override
	public InputChannel getChannel(int channelIndex) {
		int gateIndex = inputChannelToInputGateIndex[channelIndex];
		return inputGatesByGateIndex.get(gateIndex).getChannel(channelIndex - inputGateChannelIndexOffsets[gateIndex]);
	}

	@Override
	public boolean isFinished() {
		return inputGatesWithRemainingData.isEmpty();
	}

	@Override
	public Optional<BufferOrEvent> getNext() throws IOException, InterruptedException {
		return getNextBufferOrEvent(true);
	}

	@Override
	public Optional<BufferOrEvent> pollNext() throws IOException, InterruptedException {

		/*************************************************
		 *
		 *  注释：
		 */
		return getNextBufferOrEvent(false);
	}

	private Optional<BufferOrEvent> getNextBufferOrEvent(boolean blocking) throws IOException, InterruptedException {
		if(inputGatesWithRemainingData.isEmpty()) {
			return Optional.empty();
		}

		// TODO_MA 注释： 从 buffer 中获取数据对象：Optional<InputWithData>
		Optional<InputWithData<IndexedInputGate, BufferOrEvent>> next = waitAndGetNextData(blocking);
		if(!next.isPresent()) {
			return Optional.empty();
		}

		// TODO_MA 注释： 真正拿到 InputWithData
		InputWithData<IndexedInputGate, BufferOrEvent> inputWithData = next.get();

		handleEndOfPartitionEvent(inputWithData.data, inputWithData.input);

		// TODO_MA 注释： 这个 InputGate 中还有更多的数据，继续加入队列
		if(!inputWithData.data.moreAvailable()) {
			inputWithData.data.setMoreAvailable(inputWithData.moreAvailable);
		}

		return Optional.of(inputWithData.data);
	}

	private Optional<InputWithData<IndexedInputGate, BufferOrEvent>> waitAndGetNextData(
		boolean blocking) throws IOException, InterruptedException {

		while(true) {

			// TODO_MA 注释： 获取 InputGate
			Optional<IndexedInputGate> inputGate = getInputGate(blocking);
			if(!inputGate.isPresent()) {
				return Optional.empty();
			}

			/*************************************************
			 *
			 *  注释： 获取数据
			 */
			// In case of inputGatesWithData being inaccurate do not block on an empty inputGate, but just poll the data.
			// Do not poll the gate under inputGatesWithData lock, since this can trigger notifications
			// that could deadlock because of wrong locks taking order.
			Optional<BufferOrEvent> bufferOrEvent = inputGate.get().pollNext();

			synchronized(inputGatesWithData) {

				// TODO_MA 注释： 等待 inputGatesWithData 队列，经典的生产者-消费者模型
				if(bufferOrEvent.isPresent() && bufferOrEvent.get().moreAvailable()) {
					// enqueue the inputGate at the end to avoid starvation
					inputGatesWithData.add(inputGate.get());

				} else if(!inputGate.get().isFinished()) {
					inputGate.get().getAvailableFuture().thenRun(() -> queueInputGate(inputGate.get()));
				}

				if(inputGatesWithData.isEmpty()) {
					availabilityHelper.resetUnavailable();
				}

				if(bufferOrEvent.isPresent()) {
					return Optional.of(new InputWithData<>(inputGate.get(), bufferOrEvent.get(), !inputGatesWithData.isEmpty()));
				}
			}
		}
	}

	private void handleEndOfPartitionEvent(BufferOrEvent bufferOrEvent, InputGate inputGate) {
		if(bufferOrEvent.isEvent() && bufferOrEvent.getEvent().getClass() == EndOfPartitionEvent.class && inputGate.isFinished()) {

			checkState(!bufferOrEvent.moreAvailable());
			if(!inputGatesWithRemainingData.remove(inputGate)) {
				throw new IllegalStateException("Couldn't find input gate in set of remaining " + "input gates.");
			}
			if(isFinished()) {
				markAvailable();
			}
		}
	}

	private void markAvailable() {
		CompletableFuture<?> toNotify;
		synchronized(inputGatesWithData) {
			toNotify = availabilityHelper.getUnavailableToResetAvailable();
		}
		toNotify.complete(null);
	}

	@Override
	public void sendTaskEvent(TaskEvent event) throws IOException {
		for(InputGate inputGate : inputGatesByGateIndex.values()) {
			inputGate.sendTaskEvent(event);
		}
	}

	@Override
	public void resumeConsumption(int channelIndex) throws IOException {
		// BEWARE: consumption resumption only happens for streaming jobs in which all
		// slots are allocated together so there should be no UnknownInputChannel. We
		// will refactor the code to not rely on this assumption in the future.
		getChannel(channelIndex).resumeConsumption();
	}

	@Override
	public void setup() {
	}

	@Override
	public CompletableFuture<?> readRecoveredState(ExecutorService executor, ChannelStateReader reader) {
		throw new UnsupportedOperationException("This method should never be called.");
	}

	@Override
	public void requestPartitions() throws IOException {
		for(InputGate inputGate : inputGatesByGateIndex.values()) {

			/*************************************************
			 *
			 *  注释：
			 */
			inputGate.requestPartitions();
		}
	}

	@Override
	public void close() throws IOException {
	}

	private void queueInputGate(IndexedInputGate inputGate) {
		checkNotNull(inputGate);

		CompletableFuture<?> toNotify = null;

		synchronized(inputGatesWithData) {
			if(inputGatesWithData.contains(inputGate)) {
				return;
			}

			int availableInputGates = inputGatesWithData.size();

			inputGatesWithData.add(inputGate);

			if(availableInputGates == 0) {
				inputGatesWithData.notifyAll();
				toNotify = availabilityHelper.getUnavailableToResetAvailable();
			}
		}

		if(toNotify != null) {
			toNotify.complete(null);
		}
	}

	private Optional<IndexedInputGate> getInputGate(boolean blocking) throws InterruptedException {
		synchronized(inputGatesWithData) {
			while(inputGatesWithData.size() == 0) {
				if(blocking) {
					inputGatesWithData.wait();
				} else {
					availabilityHelper.resetUnavailable();
					return Optional.empty();
				}
			}
			Iterator<IndexedInputGate> inputGateIterator = inputGatesWithData.iterator();
			IndexedInputGate inputGate = inputGateIterator.next();
			inputGateIterator.remove();
			return Optional.of(inputGate);
		}
	}

	@Override
	public void registerBufferReceivedListener(BufferReceivedListener listener) {
		for(InputGate inputGate : inputGatesByGateIndex.values()) {
			inputGate.registerBufferReceivedListener(listener);
		}
	}
}
