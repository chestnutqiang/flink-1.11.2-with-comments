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

package org.apache.flink.client.program.rest;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.JobStatus;
import org.apache.flink.api.common.accumulators.AccumulatorHelper;
import org.apache.flink.api.common.cache.DistributedCache;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.client.program.ClusterClient;
import org.apache.flink.client.program.rest.retry.ExponentialWaitStrategy;
import org.apache.flink.client.program.rest.retry.WaitStrategy;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.core.fs.Path;
import org.apache.flink.runtime.client.JobStatusMessage;
import org.apache.flink.runtime.client.JobSubmissionException;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.concurrent.ScheduledExecutorServiceAdapter;
import org.apache.flink.runtime.highavailability.ClientHighAvailabilityServices;
import org.apache.flink.runtime.highavailability.HighAvailabilityServicesUtils;
import org.apache.flink.runtime.jobgraph.JobGraph;
import org.apache.flink.runtime.jobgraph.OperatorID;
import org.apache.flink.runtime.jobmaster.JobResult;
import org.apache.flink.runtime.leaderretrieval.LeaderRetrievalService;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.operators.coordination.CoordinationRequest;
import org.apache.flink.runtime.operators.coordination.CoordinationResponse;
import org.apache.flink.runtime.rest.FileUpload;
import org.apache.flink.runtime.rest.RestClient;
import org.apache.flink.runtime.rest.handler.async.AsynchronousOperationInfo;
import org.apache.flink.runtime.rest.handler.async.TriggerResponse;
import org.apache.flink.runtime.rest.messages.EmptyMessageParameters;
import org.apache.flink.runtime.rest.messages.EmptyRequestBody;
import org.apache.flink.runtime.rest.messages.EmptyResponseBody;
import org.apache.flink.runtime.rest.messages.JobAccumulatorsHeaders;
import org.apache.flink.runtime.rest.messages.JobAccumulatorsInfo;
import org.apache.flink.runtime.rest.messages.JobAccumulatorsMessageParameters;
import org.apache.flink.runtime.rest.messages.JobCancellationHeaders;
import org.apache.flink.runtime.rest.messages.JobCancellationMessageParameters;
import org.apache.flink.runtime.rest.messages.JobMessageParameters;
import org.apache.flink.runtime.rest.messages.JobsOverviewHeaders;
import org.apache.flink.runtime.rest.messages.MessageHeaders;
import org.apache.flink.runtime.rest.messages.MessageParameters;
import org.apache.flink.runtime.rest.messages.RequestBody;
import org.apache.flink.runtime.rest.messages.ResponseBody;
import org.apache.flink.runtime.rest.messages.TerminationModeQueryParameter;
import org.apache.flink.runtime.rest.messages.TriggerId;
import org.apache.flink.runtime.rest.messages.cluster.ShutdownHeaders;
import org.apache.flink.runtime.rest.messages.job.JobDetailsHeaders;
import org.apache.flink.runtime.rest.messages.job.JobDetailsInfo;
import org.apache.flink.runtime.rest.messages.job.JobExecutionResultHeaders;
import org.apache.flink.runtime.rest.messages.job.JobSubmitHeaders;
import org.apache.flink.runtime.rest.messages.job.JobSubmitRequestBody;
import org.apache.flink.runtime.rest.messages.job.JobSubmitResponseBody;
import org.apache.flink.runtime.rest.messages.job.coordination.ClientCoordinationHeaders;
import org.apache.flink.runtime.rest.messages.job.coordination.ClientCoordinationMessageParameters;
import org.apache.flink.runtime.rest.messages.job.coordination.ClientCoordinationRequestBody;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointDisposalRequest;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointDisposalStatusHeaders;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointDisposalStatusMessageParameters;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointDisposalTriggerHeaders;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointInfo;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointStatusHeaders;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointStatusMessageParameters;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointTriggerHeaders;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointTriggerMessageParameters;
import org.apache.flink.runtime.rest.messages.job.savepoints.SavepointTriggerRequestBody;
import org.apache.flink.runtime.rest.messages.job.savepoints.stop.StopWithSavepointRequestBody;
import org.apache.flink.runtime.rest.messages.job.savepoints.stop.StopWithSavepointTriggerHeaders;
import org.apache.flink.runtime.rest.messages.queue.AsynchronouslyCreatedResource;
import org.apache.flink.runtime.rest.messages.queue.QueueStatus;
import org.apache.flink.runtime.rest.util.RestClientException;
import org.apache.flink.runtime.rest.util.RestConstants;
import org.apache.flink.runtime.util.ExecutorThreadFactory;
import org.apache.flink.runtime.webmonitor.retriever.LeaderRetriever;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.ExecutorUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.SerializedValue;
import org.apache.flink.util.function.CheckedSupplier;

import org.apache.flink.shaded.netty4.io.netty.channel.ConnectTimeoutException;
import org.apache.flink.shaded.netty4.io.netty.handler.codec.http.HttpResponseStatus;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import java.io.IOException;
import java.io.ObjectOutputStream;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * A {@link ClusterClient} implementation that communicates via HTTP REST requests.
 */
public class RestClusterClient<T> implements ClusterClient<T> {

	private static final Logger LOG = LoggerFactory.getLogger(RestClusterClient.class);

	private final RestClusterClientConfiguration restClusterClientConfiguration;

	private final Configuration configuration;

	private final RestClient restClient;

	private final ExecutorService executorService = Executors.newFixedThreadPool(4, new ExecutorThreadFactory("Flink-RestClusterClient-IO"));

	private final WaitStrategy waitStrategy;

	private final T clusterId;

	private final ClientHighAvailabilityServices clientHAServices;

	private final LeaderRetrievalService webMonitorRetrievalService;

	private final LeaderRetriever webMonitorLeaderRetriever = new LeaderRetriever();

	private final AtomicBoolean running = new AtomicBoolean(true);

	/**
	 * ExecutorService to run operations that can be retried on exceptions.
	 */
	private ScheduledExecutorService retryExecutorService;

	public RestClusterClient(Configuration config, T clusterId) throws Exception {

		/*************************************************
		 *
		 *  注释： 第三个参数： ZooKeeperClientHAServices
		 */
		this(config, clusterId, HighAvailabilityServicesUtils.createClientHAService(config));
	}

	public RestClusterClient(Configuration config, T clusterId, ClientHighAvailabilityServices clientHAServices) throws Exception {

		/*************************************************
		 *
		 *  注释：
		 */
		this(config, null, clusterId, new ExponentialWaitStrategy(10L, 2000L), clientHAServices);
	}

	@VisibleForTesting
	RestClusterClient(Configuration configuration, @Nullable RestClient restClient, T clusterId, WaitStrategy waitStrategy) throws Exception {
		this(configuration, restClient, clusterId, waitStrategy, HighAvailabilityServicesUtils.createClientHAService(configuration));
	}

	private RestClusterClient(Configuration configuration, @Nullable RestClient restClient, T clusterId, WaitStrategy waitStrategy,
		ClientHighAvailabilityServices clientHAServices) throws Exception {
		this.configuration = checkNotNull(configuration);

		this.restClusterClientConfiguration = RestClusterClientConfiguration.fromConfiguration(configuration);

		if(restClient != null) {
			this.restClient = restClient;
		} else {

			// TODO_MA 注释： RestClient 客户端
			this.restClient = new RestClient(restClusterClientConfiguration.getRestClientConfiguration(), executorService);
		}

		this.waitStrategy = checkNotNull(waitStrategy);
		this.clusterId = checkNotNull(clusterId);

		this.clientHAServices = checkNotNull(clientHAServices);

		this.webMonitorRetrievalService = clientHAServices.getClusterRestEndpointLeaderRetriever();
		this.retryExecutorService = Executors.newSingleThreadScheduledExecutor(new ExecutorThreadFactory("Flink-RestClusterClient-Retry"));

		/*************************************************
		 *
		 *  注释： 启动
		 */
		startLeaderRetrievers();
	}

	private void startLeaderRetrievers() throws Exception {

		/*************************************************
		 *
		 *  注释： 启动监听
		 */
		this.webMonitorRetrievalService.start(webMonitorLeaderRetriever);
	}

	@Override
	public Configuration getFlinkConfiguration() {
		return new Configuration(configuration);
	}

	@Override
	public void close() {
		if(running.compareAndSet(true, false)) {
			ExecutorUtils.gracefulShutdown(restClusterClientConfiguration.getRetryDelay(), TimeUnit.MILLISECONDS, retryExecutorService);

			this.restClient.shutdown(Time.seconds(5));
			ExecutorUtils.gracefulShutdown(5, TimeUnit.SECONDS, this.executorService);

			try {
				webMonitorRetrievalService.stop();
			} catch(Exception e) {
				LOG.error("An error occurred during stopping the WebMonitorRetrievalService", e);
			}

			try {
				clientHAServices.close();
			} catch(Exception e) {
				LOG.error("An error occurred during stopping the ClientHighAvailabilityServices", e);
			}
		}
	}

	/**
	 * Requests the job details.
	 *
	 * @param jobId The job id
	 * @return Job details
	 */
	public CompletableFuture<JobDetailsInfo> getJobDetails(JobID jobId) {
		final JobDetailsHeaders detailsHeaders = JobDetailsHeaders.getInstance();
		final JobMessageParameters params = new JobMessageParameters();
		params.jobPathParameter.resolve(jobId);

		return sendRequest(detailsHeaders, params);
	}

	@Override
	public CompletableFuture<JobStatus> getJobStatus(JobID jobId) {
		return getJobDetails(jobId).thenApply(JobDetailsInfo::getJobStatus);
	}

	/**
	 * Requests the {@link JobResult} for the given {@link JobID}. The method retries multiple
	 * times to poll the {@link JobResult} before giving up.
	 *
	 * @param jobId specifying the job for which to retrieve the {@link JobResult}
	 * @return Future which is completed with the {@link JobResult} once the job has completed or
	 * with a failure if the {@link JobResult} could not be retrieved.
	 */
	@Override
	public CompletableFuture<JobResult> requestJobResult(@Nonnull JobID jobId) {
		return pollResourceAsync(() -> {
			final JobMessageParameters messageParameters = new JobMessageParameters();
			messageParameters.jobPathParameter.resolve(jobId);
			return sendRequest(JobExecutionResultHeaders.getInstance(), messageParameters);
		});
	}

	@Override
	public CompletableFuture<JobID> submitJob(@Nonnull JobGraph jobGraph) {

		/*************************************************
		 *
		 *  注释： 先持久化： 把 JobGragh 持久化到磁盘文件形成 jobGraphFile
		 *  1、持久化 JobGragh 的前缀：flink-jobgraph
		 *  2、持久化 JobGragh 的后缀：.bin
		 *  当我们把 JobGraph 持久化了之后，变成了一个文件： jobGraphFile
		 *  然后其实，在提交 JobGraph 到 Flink 集群运行额时候，其实提交的就是： 这个文件！
		 *  将来，最终是有 FLink 集群的 WebMonitor（JobSubmitHandler） 去接收请求来执行处理
		 *  JobSubmitHandler 在执行处理的第一件事情： 把传送过来的这个文件反序列化得到 JobGraph 这个对象
		 */
		CompletableFuture<java.nio.file.Path> jobGraphFileFuture = CompletableFuture.supplyAsync(() -> {
			try {
				final java.nio.file.Path jobGraphFile = Files.createTempFile("flink-jobgraph", ".bin");
				try(ObjectOutputStream objectOut = new ObjectOutputStream(Files.newOutputStream(jobGraphFile))) {
					objectOut.writeObject(jobGraph);
				}
				return jobGraphFile;
			} catch(IOException e) {
				throw new CompletionException(new FlinkException("Failed to serialize JobGraph.", e));
			}
		}, executorService);

		/*************************************************
		 *
		 *  注释： 等待持久化完成之后，然后加入带上传文件系列
		 *  补充： thenApply 接收一个函数作为参数，使用该函数处理上一个 CompletableFuture 调用的结果，并返回一个具有处理结果的 Future 对象。
		 */
		CompletableFuture<Tuple2<JobSubmitRequestBody, Collection<FileUpload>>> requestFuture = jobGraphFileFuture.thenApply(jobGraphFile -> {
			List<String> jarFileNames = new ArrayList<>(8);
			List<JobSubmitRequestBody.DistributedCacheFile> artifactFileNames = new ArrayList<>(8);
			Collection<FileUpload> filesToUpload = new ArrayList<>(8);

			// TODO_MA 注释： 加入待上传的文件系列
			filesToUpload.add(new FileUpload(jobGraphFile, RestConstants.CONTENT_TYPE_BINARY));

			for(Path jar : jobGraph.getUserJars()) {
				jarFileNames.add(jar.getName());
				filesToUpload.add(new FileUpload(Paths.get(jar.toUri()), RestConstants.CONTENT_TYPE_JAR));
			}

			for(Map.Entry<String, DistributedCache.DistributedCacheEntry> artifacts : jobGraph.getUserArtifacts().entrySet()) {
				final Path artifactFilePath = new Path(artifacts.getValue().filePath);
				try {
					// Only local artifacts need to be uploaded.
					if(!artifactFilePath.getFileSystem().isDistributedFS()) {
						artifactFileNames.add(new JobSubmitRequestBody.DistributedCacheFile(artifacts.getKey(), artifactFilePath.getName()));
						filesToUpload.add(new FileUpload(Paths.get(artifacts.getValue().filePath), RestConstants.CONTENT_TYPE_BINARY));
					}
				} catch(IOException e) {
					throw new CompletionException(new FlinkException("Failed to get the FileSystem of artifact " + artifactFilePath + ".", e));
				}
			}

			// TODO_MA 注释： 构建提交任务的请求体，包含对应的一些资源， 主要是 jobGragh 的持久化文件 和 对应的依赖jar
			final JobSubmitRequestBody requestBody = new JobSubmitRequestBody(jobGraphFile.getFileName().toString(), jarFileNames, artifactFileNames);

			// TODO_MA 注释： 返回一个 Tuple2, 其实包含两个返回结果： requestBody 和 filesToUpload
			return Tuple2.of(requestBody, Collections.unmodifiableCollection(filesToUpload));
		});

		/*************************************************
		 *
		 *  注释： 发送请求
		 *  requestFuture.thenCompos 的参数函数的参数，是 requestFuture 的返回结果，就是 Tuple2
		 *  补充：thenCompose 的参数为一个返回 CompletableFuture 实例的函数，该函数的参数是先前计算步骤的结果。
		 */
		final CompletableFuture<JobSubmitResponseBody> submissionFuture = requestFuture.thenCompose(

			// TODO_MA 注释：sendRetriableRequest() 提交
			requestAndFileUploads -> sendRetriableRequest(JobSubmitHeaders.getInstance(), EmptyMessageParameters.getInstance(), requestAndFileUploads.f0,
				requestAndFileUploads.f1, isConnectionProblemOrServiceUnavailable()));

		/*************************************************
		 *
		 *  注释：
		 *  submissionFuture.thenAccept(） 只对结果执行Action，而不返回新的计算值。
		 *  submissionFuture.thenCombine(） 合并两个线程任务的结果，并进一步处理
		 */
		submissionFuture.thenCombine(jobGraphFileFuture, (ignored, jobGraphFile) -> jobGraphFile).thenAccept(jobGraphFile -> {
			try {

				// TODO_MA 注释： 等 sendRetriableRequest 提交完成之后，删除生成的 jobGraghFile
				Files.delete(jobGraphFile);
			} catch(IOException e) {
				LOG.warn("Could not delete temporary file {}.", jobGraphFile, e);
			}
		});

		// TODO_MA 注释： thenApply 接收一个函数作为参数，使用该函数处理上一个 CompletableFuture 调用的结果，并返回一个具有处理结果的 Future 对象。
		// TODO_MA 注释： exceptionally 跑出异常的时候，执行特定的处理
		return submissionFuture.thenApply(ignore -> jobGraph.getJobID()).exceptionally((Throwable throwable) -> {
			throw new CompletionException(
				new JobSubmissionException(jobGraph.getJobID(), "Failed to submit JobGraph.", ExceptionUtils.stripCompletionException(throwable)));
		});
	}

	@Override
	public CompletableFuture<Acknowledge> cancel(JobID jobID) {
		JobCancellationMessageParameters params = new JobCancellationMessageParameters();
		params.jobPathParameter.resolve(jobID);
		params.terminationModeQueryParameter.resolve(Collections.singletonList(TerminationModeQueryParameter.TerminationMode.CANCEL));
		CompletableFuture<EmptyResponseBody> responseFuture = sendRequest(JobCancellationHeaders.getInstance(), params);
		return responseFuture.thenApply(ignore -> Acknowledge.get());
	}

	@Override
	public CompletableFuture<String> stopWithSavepoint(final JobID jobId, final boolean advanceToEndOfTime, @Nullable final String savepointDirectory) {

		final StopWithSavepointTriggerHeaders stopWithSavepointTriggerHeaders = StopWithSavepointTriggerHeaders.getInstance();

		final SavepointTriggerMessageParameters stopWithSavepointTriggerMessageParameters = stopWithSavepointTriggerHeaders.getUnresolvedMessageParameters();
		stopWithSavepointTriggerMessageParameters.jobID.resolve(jobId);

		final CompletableFuture<TriggerResponse> responseFuture = sendRequest(stopWithSavepointTriggerHeaders, stopWithSavepointTriggerMessageParameters,
			new StopWithSavepointRequestBody(savepointDirectory, advanceToEndOfTime));

		return responseFuture.thenCompose(savepointTriggerResponseBody -> {
			final TriggerId savepointTriggerId = savepointTriggerResponseBody.getTriggerId();
			return pollSavepointAsync(jobId, savepointTriggerId);
		}).thenApply(savepointInfo -> {
			if(savepointInfo.getFailureCause() != null) {
				throw new CompletionException(savepointInfo.getFailureCause());
			}
			return savepointInfo.getLocation();
		});
	}

	@Override
	public CompletableFuture<String> cancelWithSavepoint(JobID jobId, @Nullable String savepointDirectory) {
		return triggerSavepoint(jobId, savepointDirectory, true);
	}

	@Override
	public CompletableFuture<String> triggerSavepoint(final JobID jobId, final @Nullable String savepointDirectory) {
		return triggerSavepoint(jobId, savepointDirectory, false);
	}

	@Override
	public CompletableFuture<CoordinationResponse> sendCoordinationRequest(JobID jobId, OperatorID operatorId, CoordinationRequest request) {
		ClientCoordinationHeaders headers = ClientCoordinationHeaders.getInstance();
		ClientCoordinationMessageParameters params = new ClientCoordinationMessageParameters();
		params.jobPathParameter.resolve(jobId);
		params.operatorPathParameter.resolve(operatorId);

		SerializedValue<CoordinationRequest> serializedRequest;
		try {
			serializedRequest = new SerializedValue<>(request);
		} catch(IOException e) {
			return FutureUtils.completedExceptionally(e);
		}

		ClientCoordinationRequestBody requestBody = new ClientCoordinationRequestBody(serializedRequest);
		return sendRequest(headers, params, requestBody).thenApply(responseBody -> {
			try {
				return responseBody.getSerializedCoordinationResponse().deserializeValue(getClass().getClassLoader());
			} catch(IOException | ClassNotFoundException e) {
				throw new CompletionException("Failed to deserialize coordination response", e);
			}
		});
	}

	private CompletableFuture<String> triggerSavepoint(final JobID jobId, final @Nullable String savepointDirectory, final boolean cancelJob) {
		final SavepointTriggerHeaders savepointTriggerHeaders = SavepointTriggerHeaders.getInstance();
		final SavepointTriggerMessageParameters savepointTriggerMessageParameters = savepointTriggerHeaders.getUnresolvedMessageParameters();
		savepointTriggerMessageParameters.jobID.resolve(jobId);

		final CompletableFuture<TriggerResponse> responseFuture = sendRequest(savepointTriggerHeaders, savepointTriggerMessageParameters,
			new SavepointTriggerRequestBody(savepointDirectory, cancelJob));

		return responseFuture.thenCompose(savepointTriggerResponseBody -> {
			final TriggerId savepointTriggerId = savepointTriggerResponseBody.getTriggerId();
			return pollSavepointAsync(jobId, savepointTriggerId);
		}).thenApply(savepointInfo -> {
			if(savepointInfo.getFailureCause() != null) {
				throw new CompletionException(savepointInfo.getFailureCause());
			}
			return savepointInfo.getLocation();
		});
	}

	@Override
	public CompletableFuture<Map<String, Object>> getAccumulators(JobID jobID, ClassLoader loader) {
		final JobAccumulatorsHeaders accumulatorsHeaders = JobAccumulatorsHeaders.getInstance();
		final JobAccumulatorsMessageParameters accMsgParams = accumulatorsHeaders.getUnresolvedMessageParameters();
		accMsgParams.jobPathParameter.resolve(jobID);
		accMsgParams.includeSerializedAccumulatorsParameter.resolve(Collections.singletonList(true));

		CompletableFuture<JobAccumulatorsInfo> responseFuture = sendRequest(accumulatorsHeaders, accMsgParams);

		return responseFuture.thenApply(JobAccumulatorsInfo::getSerializedUserAccumulators).thenApply(accumulators -> {
			try {
				return AccumulatorHelper.deserializeAndUnwrapAccumulators(accumulators, loader);
			} catch(Exception e) {
				throw new CompletionException("Cannot deserialize and unwrap accumulators properly.", e);
			}
		});
	}

	private CompletableFuture<SavepointInfo> pollSavepointAsync(final JobID jobId, final TriggerId triggerID) {
		return pollResourceAsync(() -> {
			final SavepointStatusHeaders savepointStatusHeaders = SavepointStatusHeaders.getInstance();
			final SavepointStatusMessageParameters savepointStatusMessageParameters = savepointStatusHeaders.getUnresolvedMessageParameters();
			savepointStatusMessageParameters.jobIdPathParameter.resolve(jobId);
			savepointStatusMessageParameters.triggerIdPathParameter.resolve(triggerID);
			return sendRequest(savepointStatusHeaders, savepointStatusMessageParameters);
		});
	}

	@Override
	public CompletableFuture<Collection<JobStatusMessage>> listJobs() {
		return sendRequest(JobsOverviewHeaders.getInstance()).thenApply((multipleJobsDetails) -> multipleJobsDetails.getJobs().stream()
			.map(detail -> new JobStatusMessage(detail.getJobId(), detail.getJobName(), detail.getStatus(), detail.getStartTime()))
			.collect(Collectors.toList()));
	}

	@Override
	public T getClusterId() {
		return clusterId;
	}

	@Override
	public CompletableFuture<Acknowledge> disposeSavepoint(String savepointPath) {
		final SavepointDisposalRequest savepointDisposalRequest = new SavepointDisposalRequest(savepointPath);

		final CompletableFuture<TriggerResponse> savepointDisposalTriggerFuture = sendRequest(SavepointDisposalTriggerHeaders.getInstance(),
			savepointDisposalRequest);

		final CompletableFuture<AsynchronousOperationInfo> savepointDisposalFuture = savepointDisposalTriggerFuture
			.thenCompose((TriggerResponse triggerResponse) -> {
				final TriggerId triggerId = triggerResponse.getTriggerId();
				final SavepointDisposalStatusHeaders savepointDisposalStatusHeaders = SavepointDisposalStatusHeaders.getInstance();
				final SavepointDisposalStatusMessageParameters savepointDisposalStatusMessageParameters = savepointDisposalStatusHeaders
					.getUnresolvedMessageParameters();
				savepointDisposalStatusMessageParameters.triggerIdPathParameter.resolve(triggerId);

				return pollResourceAsync(() -> sendRequest(savepointDisposalStatusHeaders, savepointDisposalStatusMessageParameters));
			});

		return savepointDisposalFuture.thenApply((AsynchronousOperationInfo asynchronousOperationInfo) -> {
			if(asynchronousOperationInfo.getFailureCause() == null) {
				return Acknowledge.get();
			} else {
				throw new CompletionException(asynchronousOperationInfo.getFailureCause());
			}
		});
	}

	@Override
	public void shutDownCluster() {
		try {
			sendRequest(ShutdownHeaders.getInstance()).get();
		} catch(InterruptedException e) {
			Thread.currentThread().interrupt();
		} catch(ExecutionException e) {
			LOG.error("Error while shutting down cluster", e);
		}
	}

	/**
	 * Creates a {@code CompletableFuture} that polls a {@code AsynchronouslyCreatedResource} until
	 * its {@link AsynchronouslyCreatedResource#queueStatus() QueueStatus} becomes
	 * {@link QueueStatus.Id#COMPLETED COMPLETED}. The future completes with the result of
	 * {@link AsynchronouslyCreatedResource#resource()}.
	 *
	 * @param resourceFutureSupplier The operation which polls for the
	 *                               {@code AsynchronouslyCreatedResource}.
	 * @param <R>                    The type of the resource.
	 * @param <A>                    The type of the {@code AsynchronouslyCreatedResource}.
	 * @return A {@code CompletableFuture} delivering the resource.
	 */
	private <R, A extends AsynchronouslyCreatedResource<R>> CompletableFuture<R> pollResourceAsync(
		final Supplier<CompletableFuture<A>> resourceFutureSupplier) {
		return pollResourceAsync(resourceFutureSupplier, new CompletableFuture<>(), 0);
	}

	private <R, A extends AsynchronouslyCreatedResource<R>> CompletableFuture<R> pollResourceAsync(final Supplier<CompletableFuture<A>> resourceFutureSupplier,
		final CompletableFuture<R> resultFuture, final long attempt) {

		resourceFutureSupplier.get().whenComplete((asynchronouslyCreatedResource, throwable) -> {
			if(throwable != null) {
				resultFuture.completeExceptionally(throwable);
			} else {
				if(asynchronouslyCreatedResource.queueStatus().getId() == QueueStatus.Id.COMPLETED) {
					resultFuture.complete(asynchronouslyCreatedResource.resource());
				} else {
					retryExecutorService.schedule(() -> {
						pollResourceAsync(resourceFutureSupplier, resultFuture, attempt + 1);
					}, waitStrategy.sleepTime(attempt), TimeUnit.MILLISECONDS);
				}
			}
		});

		return resultFuture;
	}

	// ======================================
	// Legacy stuff we actually implement
	// ======================================

	@Override
	public String getWebInterfaceURL() {
		try {
			return getWebMonitorBaseUrl().get().toString();
		} catch(InterruptedException | ExecutionException e) {
			ExceptionUtils.checkInterrupted(e);

			LOG.warn("Could not retrieve the web interface URL for the cluster.", e);
			return "Unknown address.";
		}
	}

	//-------------------------------------------------------------------------
	// RestClient Helper
	//-------------------------------------------------------------------------

	private <M extends MessageHeaders<EmptyRequestBody, P, U>, U extends MessageParameters, P extends ResponseBody> CompletableFuture<P> sendRequest(
		M messageHeaders, U messageParameters) {
		return sendRequest(messageHeaders, messageParameters, EmptyRequestBody.getInstance());
	}

	private <M extends MessageHeaders<R, P, EmptyMessageParameters>, R extends RequestBody, P extends ResponseBody> CompletableFuture<P> sendRequest(
		M messageHeaders, R request) {
		return sendRequest(messageHeaders, EmptyMessageParameters.getInstance(), request);
	}

	@VisibleForTesting
	<M extends MessageHeaders<EmptyRequestBody, P, EmptyMessageParameters>, P extends ResponseBody> CompletableFuture<P> sendRequest(M messageHeaders) {
		return sendRequest(messageHeaders, EmptyMessageParameters.getInstance(), EmptyRequestBody.getInstance());
	}

	@VisibleForTesting
	public <M extends MessageHeaders<R, P, U>, U extends MessageParameters, R extends RequestBody, P extends ResponseBody> CompletableFuture<P> sendRequest(
		M messageHeaders, U messageParameters, R request) {
		return sendRetriableRequest(messageHeaders, messageParameters, request, isConnectionProblemOrServiceUnavailable());
	}

	private <M extends MessageHeaders<R, P, U>, U extends MessageParameters, R extends RequestBody, P extends ResponseBody> CompletableFuture<P> sendRetriableRequest(
		M messageHeaders, U messageParameters, R request, Predicate<Throwable> retryPredicate) {
		return sendRetriableRequest(messageHeaders, messageParameters, request, Collections.emptyList(), retryPredicate);
	}

	/*************************************************
	 *
	 *  注释：
	 */
	private <M extends MessageHeaders<R, P, U>, U extends MessageParameters, R extends RequestBody, P extends ResponseBody> CompletableFuture<P> sendRetriableRequest(
		M messageHeaders, U messageParameters, R request, Collection<FileUpload> filesToUpload, Predicate<Throwable> retryPredicate) {

		/*************************************************
		 *
		 *  注释： 通过 getWebMonitorBaseUrl() 获取 JobManager 的 web 服务
		 */
		return retry(
			() -> getWebMonitorBaseUrl().thenCompose(webMonitorBaseUrl -> {
			try {

				/*************************************************
				 *
				 *  注释： restClient = RestClient
				 *  注意这儿： 提交 Request 给 WebMonitorEndpoint， 最终由 JobSubmitHandler 来执行请求处理
				 *  通过 Http Restful 方式提交
				 */
				return restClient
					.sendRequest(webMonitorBaseUrl.getHost(), webMonitorBaseUrl.getPort(), messageHeaders, messageParameters, request, filesToUpload);

			} catch(IOException e) {
				throw new CompletionException(e);
			}
		}), retryPredicate);
	}

	private <C> CompletableFuture<C> retry(CheckedSupplier<CompletableFuture<C>> operation, Predicate<Throwable> retryPredicate) {

		/*************************************************
		 *
		 *  注释： 重试
		 */
		return FutureUtils.retryWithDelay(CheckedSupplier.unchecked(operation), restClusterClientConfiguration.getRetryMaxAttempts(),
			Time.milliseconds(restClusterClientConfiguration.getRetryDelay()), retryPredicate, new ScheduledExecutorServiceAdapter(retryExecutorService));
	}

	private static Predicate<Throwable> isConnectionProblemOrServiceUnavailable() {
		return isConnectionProblemException().or(isServiceUnavailable());
	}

	private static Predicate<Throwable> isConnectionProblemException() {
		return (throwable) -> ExceptionUtils.findThrowable(throwable, java.net.ConnectException.class).isPresent() || ExceptionUtils
			.findThrowable(throwable, java.net.SocketTimeoutException.class).isPresent() || ExceptionUtils
			.findThrowable(throwable, ConnectTimeoutException.class).isPresent() || ExceptionUtils.findThrowable(throwable, IOException.class).isPresent();
	}

	private static Predicate<Throwable> isServiceUnavailable() {
		return httpExceptionCodePredicate(code -> code == HttpResponseStatus.SERVICE_UNAVAILABLE.code());
	}

	private static Predicate<Throwable> httpExceptionCodePredicate(Predicate<Integer> statusCodePredicate) {
		return (throwable) -> ExceptionUtils.findThrowable(throwable, RestClientException.class).map(restClientException -> {
			final int code = restClientException.getHttpResponseStatus().code();
			return statusCodePredicate.test(code);
		}).orElse(false);
	}

	@VisibleForTesting
	CompletableFuture<URL> getWebMonitorBaseUrl() {
		return FutureUtils.orTimeout(webMonitorLeaderRetriever.getLeaderFuture(), restClusterClientConfiguration.getAwaitLeaderTimeout(), TimeUnit.MILLISECONDS)
			.thenApplyAsync(leaderAddressSessionId -> {
				final String url = leaderAddressSessionId.f0;
				try {
					return new URL(url);
				} catch(MalformedURLException e) {
					throw new IllegalArgumentException("Could not parse URL from " + url, e);
				}
			}, executorService);
	}
}
