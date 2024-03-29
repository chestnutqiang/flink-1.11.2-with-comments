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

package org.apache.flink.runtime.resourcemanager;

import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.api.common.JobID;
import org.apache.flink.api.common.time.Time;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.runtime.blob.TransientBlobKey;
import org.apache.flink.runtime.clusterframework.ApplicationStatus;
import org.apache.flink.runtime.clusterframework.types.AllocationID;
import org.apache.flink.runtime.clusterframework.types.ResourceID;
import org.apache.flink.runtime.clusterframework.types.ResourceIDRetrievable;
import org.apache.flink.runtime.clusterframework.types.ResourceProfile;
import org.apache.flink.runtime.clusterframework.types.SlotID;
import org.apache.flink.runtime.concurrent.FutureUtils;
import org.apache.flink.runtime.entrypoint.ClusterInformation;
import org.apache.flink.runtime.heartbeat.HeartbeatListener;
import org.apache.flink.runtime.heartbeat.HeartbeatManager;
import org.apache.flink.runtime.heartbeat.HeartbeatServices;
import org.apache.flink.runtime.heartbeat.HeartbeatTarget;
import org.apache.flink.runtime.heartbeat.NoOpHeartbeatManager;
import org.apache.flink.runtime.highavailability.HighAvailabilityServices;
import org.apache.flink.runtime.instance.InstanceID;
import org.apache.flink.runtime.io.network.partition.DataSetMetaInfo;
import org.apache.flink.runtime.io.network.partition.ResourceManagerPartitionTracker;
import org.apache.flink.runtime.io.network.partition.ResourceManagerPartitionTrackerFactory;
import org.apache.flink.runtime.jobgraph.IntermediateDataSetID;
import org.apache.flink.runtime.jobmaster.JobMaster;
import org.apache.flink.runtime.jobmaster.JobMasterGateway;
import org.apache.flink.runtime.jobmaster.JobMasterId;
import org.apache.flink.runtime.jobmaster.JobMasterRegistrationSuccess;
import org.apache.flink.runtime.leaderelection.LeaderContender;
import org.apache.flink.runtime.leaderelection.LeaderElectionService;
import org.apache.flink.runtime.messages.Acknowledge;
import org.apache.flink.runtime.metrics.MetricNames;
import org.apache.flink.runtime.metrics.groups.ResourceManagerMetricGroup;
import org.apache.flink.runtime.registration.RegistrationResponse;
import org.apache.flink.runtime.resourcemanager.exceptions.ResourceManagerException;
import org.apache.flink.runtime.resourcemanager.exceptions.UnknownTaskExecutorException;
import org.apache.flink.runtime.resourcemanager.registration.JobManagerRegistration;
import org.apache.flink.runtime.resourcemanager.registration.WorkerRegistration;
import org.apache.flink.runtime.resourcemanager.slotmanager.ResourceActions;
import org.apache.flink.runtime.resourcemanager.slotmanager.SlotManager;
import org.apache.flink.runtime.rest.messages.LogInfo;
import org.apache.flink.runtime.rest.messages.taskmanager.TaskManagerInfo;
import org.apache.flink.runtime.rest.messages.taskmanager.ThreadDumpInfo;
import org.apache.flink.runtime.rpc.FatalErrorHandler;
import org.apache.flink.runtime.rpc.FencedRpcEndpoint;
import org.apache.flink.runtime.rpc.RpcService;
import org.apache.flink.runtime.rpc.akka.AkkaRpcServiceUtils;
import org.apache.flink.runtime.taskexecutor.FileType;
import org.apache.flink.runtime.taskexecutor.SlotReport;
import org.apache.flink.runtime.taskexecutor.TaskExecutorGateway;
import org.apache.flink.runtime.taskexecutor.TaskExecutorHeartbeatPayload;
import org.apache.flink.runtime.taskexecutor.TaskExecutorRegistrationSuccess;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

import static org.apache.flink.util.Preconditions.checkNotNull;

/**
 * // TODO_MA 注释： ResourceManager的实现。资源经理负责资源的取消/分配和簿记。
 * ResourceManager implementation. The resource manager is responsible for resource de-/allocation and bookkeeping.
 *
 * <p>It offers the following methods as part of its rpc interface to interact with him remotely:
 * <ul>
 *     // TODO_MA 注释： registerJobManager(JobMasterId, ResourceID, String, JobID, Time) 方法负责在 ResourceManager 注册 JobMaster
 *     <li>{@link #registerJobManager(JobMasterId, ResourceID, String, JobID, Time)} registers a {@link JobMaster} at the resource manager</li>
 *     // TODO_MA 注释： requestSlot(JobMasterId, SlotRequest, Time) 负责从 ResourceManager 申请
 *     <li>{@link #requestSlot(JobMasterId, SlotRequest, Time)} requests a slot from the resource manager</li>
 * </ul>
 */
public abstract class ResourceManager<WorkerType extends ResourceIDRetrievable> extends FencedRpcEndpoint<ResourceManagerId> implements ResourceManagerGateway, LeaderContender {

	public static final String RESOURCE_MANAGER_NAME = "resourcemanager";

	/**
	 * Unique id of the resource manager.
	 */
	private final ResourceID resourceId;

	/**
	 * // TODO_MA 注释： 所有已经注册过的 JobMaster, 也可以理解成 JobManager, 通过 JobID 映射管理
	 * All currently registered JobMasterGateways scoped by JobID.
	 */
	private final Map<JobID, JobManagerRegistration> jobManagerRegistrations;

	/**
	 * // TODO_MA 注释： 所有已经注册过的 JobMaster, 也可以理解成 JobManager, 通过 ResourceID 映射管理
	 * All currently registered JobMasterGateways scoped by ResourceID.
	 */
	private final Map<ResourceID, JobManagerRegistration> jmResourceIdRegistrations;

	/**
	 * Service to retrieve the job leader ids.
	 */
	private final JobLeaderIdService jobLeaderIdService;

	/**
	 * // TODO_MA 注释： key
	 * // TODO_MA 注释： value
	 * All currently registered TaskExecutors with there framework specific worker information.
	 */
	private final Map<ResourceID, WorkerRegistration<WorkerType>> taskExecutors;

	/**
	 * Ongoing registration of TaskExecutors per resource ID.
	 */
	private final Map<ResourceID, CompletableFuture<TaskExecutorGateway>> taskExecutorGatewayFutures;

	/**
	 * High availability services for leader retrieval and election.
	 */
	private final HighAvailabilityServices highAvailabilityServices;

	private final HeartbeatServices heartbeatServices;

	/**
	 * Fatal error handler.
	 */
	private final FatalErrorHandler fatalErrorHandler;

	/**
	 * The slot manager maintains the available slots.
	 */
	private final SlotManager slotManager;

	private final ResourceManagerPartitionTracker clusterPartitionTracker;

	private final ClusterInformation clusterInformation;

	private final ResourceManagerMetricGroup resourceManagerMetricGroup;

	/**
	 * The service to elect a ResourceManager leader.
	 */
	private LeaderElectionService leaderElectionService;

	/**
	 * The heartbeat manager with task managers.
	 */
	private HeartbeatManager<TaskExecutorHeartbeatPayload, Void> taskManagerHeartbeatManager;

	/**
	 * The heartbeat manager with job managers.
	 */
	private HeartbeatManager<Void, Void> jobManagerHeartbeatManager;

	/**
	 * Represents asynchronous state clearing work.
	 *
	 * @see #clearStateAsync()
	 * @see #clearStateInternal()
	 */
	private CompletableFuture<Void> clearStateFuture = CompletableFuture.completedFuture(null);

	public ResourceManager(RpcService rpcService, ResourceID resourceId, HighAvailabilityServices highAvailabilityServices,
		HeartbeatServices heartbeatServices, SlotManager slotManager, ResourceManagerPartitionTrackerFactory clusterPartitionTrackerFactory,
		JobLeaderIdService jobLeaderIdService, ClusterInformation clusterInformation, FatalErrorHandler fatalErrorHandler,
		ResourceManagerMetricGroup resourceManagerMetricGroup, Time rpcTimeout) {

		/*************************************************
		 *
		 *  注释： 当执行完毕这个构造方法的时候，会触发调用 onStart() 方法执行
		 */
		super(rpcService, AkkaRpcServiceUtils.createRandomName(RESOURCE_MANAGER_NAME), null);

		this.resourceId = checkNotNull(resourceId);
		this.highAvailabilityServices = checkNotNull(highAvailabilityServices);
		this.heartbeatServices = checkNotNull(heartbeatServices);
		this.slotManager = checkNotNull(slotManager);
		this.jobLeaderIdService = checkNotNull(jobLeaderIdService);
		this.clusterInformation = checkNotNull(clusterInformation);
		this.fatalErrorHandler = checkNotNull(fatalErrorHandler);
		this.resourceManagerMetricGroup = checkNotNull(resourceManagerMetricGroup);

		this.jobManagerRegistrations = new HashMap<>(4);
		this.jmResourceIdRegistrations = new HashMap<>(4);
		this.taskExecutors = new HashMap<>(8);
		this.taskExecutorGatewayFutures = new HashMap<>(8);

		this.jobManagerHeartbeatManager = NoOpHeartbeatManager.getInstance();
		this.taskManagerHeartbeatManager = NoOpHeartbeatManager.getInstance();

		this.clusterPartitionTracker = checkNotNull(clusterPartitionTrackerFactory).get(
			(taskExecutorResourceId, dataSetIds) -> taskExecutors.get(taskExecutorResourceId).getTaskExecutorGateway()
				.releaseClusterPartitions(dataSetIds, rpcTimeout).exceptionally(throwable -> {
					log.debug("Request for release of cluster partitions belonging to data sets {} was not successful.", dataSetIds, throwable);
					throw new CompletionException(throwable);
				}));
	}


	// ------------------------------------------------------------------------
	//  RPC lifecycle methods
	// ------------------------------------------------------------------------

	/*************************************************
	 *
	 *  注释： 如果代码执行到这儿，也就意味着： ResourceManager 的实例化已经完成了。
	 *  接下来执行初始化：
	 */

	@Override
	public void onStart() throws Exception {
		try {

			/*************************************************
			 *
			 *  注释： 开启 RM 服务
			 */
			startResourceManagerServices();

		} catch(Exception e) {
			final ResourceManagerException exception = new ResourceManagerException(
				String.format("Could not start the ResourceManager %s", getAddress()), e);
			onFatalError(exception);
			throw exception;
		}
	}

	private void startResourceManagerServices() throws Exception {
		try {
			/*************************************************
			 *
			 *  注释： leaderElectionService = ZooKeeperLeaderElectionService
			 */
			leaderElectionService = highAvailabilityServices.getResourceManagerLeaderElectionService();

			// TODO_MA 注释： 其实在 Standalone 模式下，什么也没做
			initialize();

			// TODO_MA 注释： 注意这个 this 对象
			// TODO_MA 注释： 执行选举，成功之后，调用 leaderElectionService.isLeader()
			// TODO_MA 注释： this = ResourceManager
			leaderElectionService.start(this);

			jobLeaderIdService.start(new JobLeaderIdActionsImpl());
			registerTaskExecutorMetrics();

		} catch(Exception e) {
			handleStartResourceManagerServicesException(e);
		}
	}

	private void handleStartResourceManagerServicesException(Exception e) throws Exception {
		try {
			stopResourceManagerServices();
		} catch(Exception inner) {
			e.addSuppressed(inner);
		}

		throw e;
	}

	@Override
	public CompletableFuture<Void> onStop() {
		try {
			stopResourceManagerServices();
		} catch(Exception exception) {
			return FutureUtils.completedExceptionally(new FlinkException("Could not properly shut down the ResourceManager.", exception));
		}

		return CompletableFuture.completedFuture(null);
	}

	private void stopResourceManagerServices() throws Exception {
		Exception exception = null;

		stopHeartbeatServices();

		try {
			slotManager.close();
		} catch(Exception e) {
			exception = ExceptionUtils.firstOrSuppressed(e, exception);
		}

		try {
			leaderElectionService.stop();
		} catch(Exception e) {
			exception = ExceptionUtils.firstOrSuppressed(e, exception);
		}

		try {
			jobLeaderIdService.stop();
		} catch(Exception e) {
			exception = ExceptionUtils.firstOrSuppressed(e, exception);
		}

		resourceManagerMetricGroup.close();

		clearStateInternal();

		ExceptionUtils.tryRethrowException(exception);
	}

	// ------------------------------------------------------------------------
	//  RPC methods
	// ------------------------------------------------------------------------

	/*************************************************
	 *
	 *  注释： 向 ResourceManager 注册一个 JobManager
	 */
	@Override
	public CompletableFuture<RegistrationResponse> registerJobManager(final JobMasterId jobMasterId, final ResourceID jobManagerResourceId,
		final String jobManagerAddress, final JobID jobId, final Time timeout) {

		checkNotNull(jobMasterId);
		checkNotNull(jobManagerResourceId);
		checkNotNull(jobManagerAddress);
		checkNotNull(jobId);

		if(!jobLeaderIdService.containsJob(jobId)) {
			try {
				jobLeaderIdService.addJob(jobId);
			} catch(Exception e) {
				ResourceManagerException exception =
					new ResourceManagerException("Could not add the job " + jobId + " to the job id leader service.", e);
				onFatalError(exception);
				log.error("Could not add job {} to job leader id service.", jobId, e);
				return FutureUtils.completedExceptionally(exception);
			}
		}

		log.info("Registering job manager {}@{} for job {}.", jobMasterId, jobManagerAddress, jobId);

		CompletableFuture<JobMasterId> jobMasterIdFuture;
		try {
			jobMasterIdFuture = jobLeaderIdService.getLeaderId(jobId);
		} catch(Exception e) {
			// we cannot check the job leader id so let's fail
			// TODO: Maybe it's also ok to skip this check in case that we cannot check the leader id
			ResourceManagerException exception = new ResourceManagerException(
				"Cannot obtain the " + "job leader id future to verify the correct job leader.", e);
			onFatalError(exception);
			log.debug("Could not obtain the job leader id future to verify the correct job leader.");
			return FutureUtils.completedExceptionally(exception);
		}

		CompletableFuture<JobMasterGateway> jobMasterGatewayFuture = getRpcService().connect(jobManagerAddress, jobMasterId, JobMasterGateway.class);

		CompletableFuture<RegistrationResponse> registrationResponseFuture = jobMasterGatewayFuture
			.thenCombineAsync(jobMasterIdFuture, (JobMasterGateway jobMasterGateway, JobMasterId leadingJobMasterId) -> {
				if(Objects.equals(leadingJobMasterId, jobMasterId)) {

					/*************************************************
					 *
					 *  注释： 注册 JobMaster
					 */
					return registerJobMasterInternal(jobMasterGateway, jobId, jobManagerAddress, jobManagerResourceId);
				} else {
					final String declineMessage = String.format(
						"The leading JobMaster id %s did not match the received JobMaster id %s. " + "This indicates that a JobMaster leader change has happened.",
						leadingJobMasterId, jobMasterId);
					log.debug(declineMessage);
					return new RegistrationResponse.Decline(declineMessage);
				}
			}, getMainThreadExecutor());

		/*************************************************
		 *
		 *  注释： RegistrationResponse = JobMasterRegistrationSuccess
		 */
		// handle exceptions which might have occurred in one of the futures inputs of combine
		return registrationResponseFuture.handleAsync((RegistrationResponse registrationResponse, Throwable throwable) -> {
			if(throwable != null) {
				if(log.isDebugEnabled()) {
					log.debug("Registration of job manager {}@{} failed.", jobMasterId, jobManagerAddress, throwable);
				} else {
					log.info("Registration of job manager {}@{} failed.", jobMasterId, jobManagerAddress);
				}
				return new RegistrationResponse.Decline(throwable.getMessage());
			} else {
				return registrationResponse;
			}
		}, getRpcService().getExecutor());
	}

	/*************************************************
	 *
	 *  注释： ResourceManager 注册 TaskExecutor
	 *  处理注册！
	 */
	@Override
	public CompletableFuture<RegistrationResponse> registerTaskExecutor(final TaskExecutorRegistration taskExecutorRegistration, final Time timeout) {

		CompletableFuture<TaskExecutorGateway> taskExecutorGatewayFuture = getRpcService()
			.connect(taskExecutorRegistration.getTaskExecutorAddress(), TaskExecutorGateway.class);

		taskExecutorGatewayFutures.put(taskExecutorRegistration.getResourceId(), taskExecutorGatewayFuture);

		return taskExecutorGatewayFuture.handleAsync((TaskExecutorGateway taskExecutorGateway, Throwable throwable) -> {
			final ResourceID resourceId = taskExecutorRegistration.getResourceId();
			if(taskExecutorGatewayFuture == taskExecutorGatewayFutures.get(resourceId)) {
				taskExecutorGatewayFutures.remove(resourceId);
				if(throwable != null) {
					return new RegistrationResponse.Decline(throwable.getMessage());
				} else {
					/*************************************************
					 *
					 *  注释： 注册
					 */
					return registerTaskExecutorInternal(taskExecutorGateway, taskExecutorRegistration);
				}
			} else {
				log.debug("Ignoring outdated TaskExecutorGateway connection for {}.", resourceId);
				return new RegistrationResponse.Decline("Decline outdated task executor registration.");
			}
		}, getMainThreadExecutor());
	}

	@Override
	public CompletableFuture<Acknowledge> sendSlotReport(ResourceID taskManagerResourceId, InstanceID taskManagerRegistrationId,
		SlotReport slotReport, Time timeout) {

		// TODO_MA 注释： 拿到该 TaskExecutor 的注册信息对象
		final WorkerRegistration<WorkerType> workerTypeWorkerRegistration = taskExecutors.get(taskManagerResourceId);

		if(workerTypeWorkerRegistration.getInstanceID().equals(taskManagerRegistrationId)) {

			/*************************************************
			 *
			 *  注释： 注册 Slot
			 */
			if(slotManager.registerTaskManager(workerTypeWorkerRegistration, slotReport)) {

				/*************************************************
				 *
				 *  注释： 返回 TaskManager 汇报成功消息
				 */
				onTaskManagerRegistration(workerTypeWorkerRegistration);
			}
			return CompletableFuture.completedFuture(Acknowledge.get());
		} else {
			return FutureUtils.completedExceptionally(
				new ResourceManagerException(String.format("Unknown TaskManager registration id %s.", taskManagerRegistrationId)));
		}
	}

	protected void onTaskManagerRegistration(WorkerRegistration<WorkerType> workerTypeWorkerRegistration) {
		// noop
	}

	@Override
	public void heartbeatFromTaskManager(final ResourceID resourceID, final TaskExecutorHeartbeatPayload heartbeatPayload) {
		taskManagerHeartbeatManager.receiveHeartbeat(resourceID, heartbeatPayload);
	}

	@Override
	public void heartbeatFromJobManager(final ResourceID resourceID) {
		jobManagerHeartbeatManager.receiveHeartbeat(resourceID, null);
	}

	@Override
	public void disconnectTaskManager(final ResourceID resourceId, final Exception cause) {
		closeTaskManagerConnection(resourceId, cause);
	}

	@Override
	public void disconnectJobManager(final JobID jobId, final Exception cause) {
		closeJobManagerConnection(jobId, cause);
	}

	@Override
	public CompletableFuture<Acknowledge> requestSlot(JobMasterId jobMasterId, SlotRequest slotRequest, final Time timeout) {

		/*************************************************
		 *
		 *  注释： 先获取 JobID， SlotRequest 中携带 JobID
		 *  判断该 Job 是否已经注册过。
		 */
		JobID jobId = slotRequest.getJobId();
		JobManagerRegistration jobManagerRegistration = jobManagerRegistrations.get(jobId);

		if(null != jobManagerRegistration) {

			// TODO_MA 注释： 判断申请 slot 的 JobMaster 和 注册的 Job 的 Master 地址是否一样
			// TODO_MA 注释： 如果不一样，则放弃。防止因为 JobMaster 迁移导致申请了双倍的slot导致资源浪费
			if(Objects.equals(jobMasterId, jobManagerRegistration.getJobMasterId())) {
				log.info("Request slot with profile {} for job {} with allocation id {}.", slotRequest.getResourceProfile(), slotRequest.getJobId(),
					slotRequest.getAllocationId());

				try {
					/*************************************************
					 *
					 *  注释： 调用 SlotManagerImpl 来申请 slot
					 */
					slotManager.registerSlotRequest(slotRequest);

				} catch(ResourceManagerException e) {
					return FutureUtils.completedExceptionally(e);
				}
				return CompletableFuture.completedFuture(Acknowledge.get());
			} else {
				return FutureUtils.completedExceptionally(new ResourceManagerException(
					"The job leader's id " + jobManagerRegistration.getJobMasterId() + " does not match the received id " + jobMasterId + '.'));
			}
		} else {
			return FutureUtils.completedExceptionally(new ResourceManagerException("Could not find registered job manager for job " + jobId + '.'));
		}
	}

	@Override
	public void cancelSlotRequest(AllocationID allocationID) {
		// As the slot allocations are async, it can not avoid all redundant slots, but should best effort.
		slotManager.unregisterSlotRequest(allocationID);
	}

	@Override
	public void notifySlotAvailable(final InstanceID instanceID, final SlotID slotId, final AllocationID allocationId) {

		final ResourceID resourceId = slotId.getResourceID();
		WorkerRegistration<WorkerType> registration = taskExecutors.get(resourceId);

		if(registration != null) {
			InstanceID registrationId = registration.getInstanceID();

			if(Objects.equals(registrationId, instanceID)) {
				slotManager.freeSlot(slotId, allocationId);
			} else {
				log.debug("Invalid registration id for slot available message. This indicates an" + " outdated request.");
			}
		} else {
			log.debug("Could not find registration for resource id {}. Discarding the slot available" + "message {}.", resourceId, slotId);
		}
	}

	/**
	 * Cleanup application and shut down cluster.
	 *
	 * @param finalStatus of the Flink application
	 * @param diagnostics diagnostics message for the Flink application or {@code null}
	 */
	@Override
	public CompletableFuture<Acknowledge> deregisterApplication(final ApplicationStatus finalStatus, @Nullable final String diagnostics) {
		log.info("Shut down cluster because application is in {}, diagnostics {}.", finalStatus, diagnostics);

		try {
			internalDeregisterApplication(finalStatus, diagnostics);
		} catch(ResourceManagerException e) {
			log.warn("Could not properly shutdown the application.", e);
		}

		return CompletableFuture.completedFuture(Acknowledge.get());
	}

	@Override
	public CompletableFuture<Integer> getNumberOfRegisteredTaskManagers() {
		return CompletableFuture.completedFuture(taskExecutors.size());
	}

	@Override
	public CompletableFuture<Collection<TaskManagerInfo>> requestTaskManagerInfo(Time timeout) {

		final ArrayList<TaskManagerInfo> taskManagerInfos = new ArrayList<>(taskExecutors.size());

		for(Map.Entry<ResourceID, WorkerRegistration<WorkerType>> taskExecutorEntry : taskExecutors.entrySet()) {
			final ResourceID resourceId = taskExecutorEntry.getKey();
			final WorkerRegistration<WorkerType> taskExecutor = taskExecutorEntry.getValue();

			taskManagerInfos.add(new TaskManagerInfo(resourceId, taskExecutor.getTaskExecutorGateway().getAddress(), taskExecutor.getDataPort(),
				taskManagerHeartbeatManager.getLastHeartbeatFrom(resourceId), slotManager.getNumberRegisteredSlotsOf(taskExecutor.getInstanceID()),
				slotManager.getNumberFreeSlotsOf(taskExecutor.getInstanceID()), slotManager.getRegisteredResourceOf(taskExecutor.getInstanceID()),
				slotManager.getFreeResourceOf(taskExecutor.getInstanceID()), taskExecutor.getHardwareDescription()));
		}

		return CompletableFuture.completedFuture(taskManagerInfos);
	}

	@Override
	public CompletableFuture<TaskManagerInfo> requestTaskManagerInfo(ResourceID resourceId, Time timeout) {

		final WorkerRegistration<WorkerType> taskExecutor = taskExecutors.get(resourceId);

		if(taskExecutor == null) {
			return FutureUtils.completedExceptionally(new UnknownTaskExecutorException(resourceId));
		} else {
			final InstanceID instanceId = taskExecutor.getInstanceID();
			final TaskManagerInfo taskManagerInfo = new TaskManagerInfo(resourceId, taskExecutor.getTaskExecutorGateway().getAddress(),
				taskExecutor.getDataPort(), taskManagerHeartbeatManager.getLastHeartbeatFrom(resourceId),
				slotManager.getNumberRegisteredSlotsOf(instanceId), slotManager.getNumberFreeSlotsOf(instanceId),
				slotManager.getRegisteredResourceOf(instanceId), slotManager.getFreeResourceOf(instanceId), taskExecutor.getHardwareDescription());

			return CompletableFuture.completedFuture(taskManagerInfo);
		}
	}

	@Override
	public CompletableFuture<ResourceOverview> requestResourceOverview(Time timeout) {
		final int numberSlots = slotManager.getNumberRegisteredSlots();
		final int numberFreeSlots = slotManager.getNumberFreeSlots();
		final ResourceProfile totalResource = slotManager.getRegisteredResource();
		final ResourceProfile freeResource = slotManager.getFreeResource();

		return CompletableFuture
			.completedFuture(new ResourceOverview(taskExecutors.size(), numberSlots, numberFreeSlots, totalResource, freeResource));
	}

	@Override
	public CompletableFuture<Collection<Tuple2<ResourceID, String>>> requestTaskManagerMetricQueryServiceAddresses(Time timeout) {
		final ArrayList<CompletableFuture<Optional<Tuple2<ResourceID, String>>>> metricQueryServiceAddressFutures = new ArrayList<>(
			taskExecutors.size());

		for(Map.Entry<ResourceID, WorkerRegistration<WorkerType>> workerRegistrationEntry : taskExecutors.entrySet()) {
			final ResourceID tmResourceId = workerRegistrationEntry.getKey();
			final WorkerRegistration<WorkerType> workerRegistration = workerRegistrationEntry.getValue();
			final TaskExecutorGateway taskExecutorGateway = workerRegistration.getTaskExecutorGateway();

			final CompletableFuture<Optional<Tuple2<ResourceID, String>>> metricQueryServiceAddressFuture = taskExecutorGateway
				.requestMetricQueryServiceAddress(timeout).thenApply(o -> o.toOptional().map(address -> Tuple2.of(tmResourceId, address)));

			metricQueryServiceAddressFutures.add(metricQueryServiceAddressFuture);
		}

		return FutureUtils.combineAll(metricQueryServiceAddressFutures)
			.thenApply(collection -> collection.stream().filter(Optional::isPresent).map(Optional::get).collect(Collectors.toList()));
	}

	@Override
	public CompletableFuture<TransientBlobKey> requestTaskManagerFileUploadByType(ResourceID taskManagerId, FileType fileType, Time timeout) {
		log.debug("Request {} file upload from TaskExecutor {}.", fileType, taskManagerId);

		final WorkerRegistration<WorkerType> taskExecutor = taskExecutors.get(taskManagerId);

		if(taskExecutor == null) {
			log.debug("Request upload of file {} from unregistered TaskExecutor {}.", fileType, taskManagerId);
			return FutureUtils.completedExceptionally(new UnknownTaskExecutorException(taskManagerId));
		} else {
			return taskExecutor.getTaskExecutorGateway().requestFileUploadByType(fileType, timeout);
		}
	}

	@Override
	public CompletableFuture<TransientBlobKey> requestTaskManagerFileUploadByName(ResourceID taskManagerId, String fileName, Time timeout) {
		log.debug("Request upload of file {} from TaskExecutor {}.", fileName, taskManagerId);

		final WorkerRegistration<WorkerType> taskExecutor = taskExecutors.get(taskManagerId);

		if(taskExecutor == null) {
			log.debug("Request upload of file {} from unregistered TaskExecutor {}.", fileName, taskManagerId);
			return FutureUtils.completedExceptionally(new UnknownTaskExecutorException(taskManagerId));
		} else {
			return taskExecutor.getTaskExecutorGateway().requestFileUploadByName(fileName, timeout);
		}
	}

	@Override
	public CompletableFuture<Collection<LogInfo>> requestTaskManagerLogList(ResourceID taskManagerId, Time timeout) {
		final WorkerRegistration<WorkerType> taskExecutor = taskExecutors.get(taskManagerId);
		if(taskExecutor == null) {
			log.debug("Requested log list from unregistered TaskExecutor {}.", taskManagerId);
			return FutureUtils.completedExceptionally(new UnknownTaskExecutorException(taskManagerId));
		} else {
			return taskExecutor.getTaskExecutorGateway().requestLogList(timeout);
		}
	}

	@Override
	public CompletableFuture<Void> releaseClusterPartitions(IntermediateDataSetID dataSetId) {
		return clusterPartitionTracker.releaseClusterPartitions(dataSetId);
	}

	@Override
	public CompletableFuture<Map<IntermediateDataSetID, DataSetMetaInfo>> listDataSets() {
		return CompletableFuture.completedFuture(clusterPartitionTracker.listDataSets());
	}

	@Override
	public CompletableFuture<ThreadDumpInfo> requestThreadDump(ResourceID taskManagerId, Time timeout) {
		final WorkerRegistration<WorkerType> taskExecutor = taskExecutors.get(taskManagerId);

		if(taskExecutor == null) {
			log.debug("Requested thread dump from unregistered TaskExecutor {}.", taskManagerId);
			return FutureUtils.completedExceptionally(new UnknownTaskExecutorException(taskManagerId));
		} else {
			return taskExecutor.getTaskExecutorGateway().requestThreadDump(timeout);
		}

	}

	// ------------------------------------------------------------------------
	//  Internal methods
	// ------------------------------------------------------------------------

	/**
	 * Registers a new JobMaster.
	 *
	 * @param jobMasterGateway     to communicate with the registering JobMaster
	 * @param jobId                of the job for which the JobMaster is responsible
	 * @param jobManagerAddress    address of the JobMaster
	 * @param jobManagerResourceId ResourceID of the JobMaster
	 * @return RegistrationResponse
	 */
	private RegistrationResponse registerJobMasterInternal(final JobMasterGateway jobMasterGateway, JobID jobId, String jobManagerAddress,
		ResourceID jobManagerResourceId) {

		/*************************************************
		 *
		 *  注释： 如果已经注册过
		 */
		if(jobManagerRegistrations.containsKey(jobId)) {
			JobManagerRegistration oldJobManagerRegistration = jobManagerRegistrations.get(jobId);

			if(Objects.equals(oldJobManagerRegistration.getJobMasterId(), jobMasterGateway.getFencingToken())) {
				// same registration
				log.debug("Job manager {}@{} was already registered.", jobMasterGateway.getFencingToken(), jobManagerAddress);
			} else {

				// TODO_MA 注释： 取消之前的 JobManager
				// tell old job manager that he is no longer the job leader
				disconnectJobManager(oldJobManagerRegistration.getJobID(), new Exception("New job leader for job " + jobId + " found."));

				// TODO_MA 注释： 重新注册
				JobManagerRegistration jobManagerRegistration = new JobManagerRegistration(jobId, jobManagerResourceId, jobMasterGateway);
				jobManagerRegistrations.put(jobId, jobManagerRegistration);
				jmResourceIdRegistrations.put(jobManagerResourceId, jobManagerRegistration);
			}
		}

		// TODO_MA 注释： 显然这才是核心逻辑
		else {
			/*************************************************
			 *
			 *  注释： 执行注册
			 */
			// new registration for the job
			JobManagerRegistration jobManagerRegistration = new JobManagerRegistration(jobId, jobManagerResourceId, jobMasterGateway);
			jobManagerRegistrations.put(jobId, jobManagerRegistration);
			jmResourceIdRegistrations.put(jobManagerResourceId, jobManagerRegistration);
		}

		log.info("Registered job manager {}@{} for job {}.", jobMasterGateway.getFencingToken(), jobManagerAddress, jobId);

		jobManagerHeartbeatManager.monitorTarget(jobManagerResourceId, new HeartbeatTarget<Void>() {
			@Override
			public void receiveHeartbeat(ResourceID resourceID, Void payload) {
				// the ResourceManager will always send heartbeat requests to the JobManager
			}

			@Override
			public void requestHeartbeat(ResourceID resourceID, Void payload) {

				/*************************************************
				 *
				 *  注释： 维持 JobMaster 和 ResourceManager 之间的心跳
				 */
				jobMasterGateway.heartbeatFromResourceManager(resourceID);
			}
		});

		/*************************************************
		 *
		 *  注释： 返回注册成功的消息
		 */
		return new JobMasterRegistrationSuccess(getFencingToken(), resourceId);
	}

	/**
	 * Registers a new TaskExecutor.
	 *
	 * @param taskExecutorRegistration task executor registration parameters
	 * @return RegistrationResponse
	 */
	private RegistrationResponse registerTaskExecutorInternal(TaskExecutorGateway taskExecutorGateway,
		TaskExecutorRegistration taskExecutorRegistration) {

		// TODO_MA 注释： 获取 TaskExecutor 的 ResourceID
		ResourceID taskExecutorResourceId = taskExecutorRegistration.getResourceId();

		/*************************************************
		 *
		 *  注释： 移除旧的注册对象
		 */
		WorkerRegistration<WorkerType> oldRegistration = taskExecutors.remove(taskExecutorResourceId);
		if(oldRegistration != null) {
			// TODO :: suggest old taskExecutor to stop itself
			log.debug("Replacing old registration of TaskExecutor {}.", taskExecutorResourceId);

			// TODO_MA 注释： 从 SlotManager 中移除旧的 TaskManager
			// remove old task manager registration from slot manager
			slotManager.unregisterTaskManager(oldRegistration.getInstanceID(),
				new ResourceManagerException(String.format("TaskExecutor %s re-connected to the ResourceManager.", taskExecutorResourceId)));
		}

		final WorkerType newWorker = workerStarted(taskExecutorResourceId);

		String taskExecutorAddress = taskExecutorRegistration.getTaskExecutorAddress();
		if(newWorker == null) {
			log.warn("Discard registration from TaskExecutor {} at ({}) because the framework did " + "not recognize it", taskExecutorResourceId,
				taskExecutorAddress);
			return new RegistrationResponse.Decline("unrecognized TaskExecutor");
		} else {

			/*************************************************
			 *
			 *  注释： 构造一个 WorkerRegistration
			 *  TaskEXecutorRegistrition
			 *  ResourceManagerRegistrition
			 *  WorkerRegistration
			 */
			WorkerRegistration<WorkerType> registration = new WorkerRegistration<>(
				taskExecutorGateway,
				newWorker,
				taskExecutorRegistration.getDataPort(),
				taskExecutorRegistration.getHardwareDescription()
			);

			/*************************************************
			 *
			 *  注释： 真正完成注册
			 *  key = ResourceID
			 *  value = WorkerRegistration
			 */
			log.info("Registering TaskManager with ResourceID {} ({}) at ResourceManager", taskExecutorResourceId, taskExecutorAddress);
			taskExecutors.put(taskExecutorResourceId, registration);

			/*************************************************
			 *
			 *  注释：  不管是那种集群的注册：
			 *  1、主节点启动注册服务
			 *  2、从节点启动
			 *  3、先封装自己的信息，通过 RPC 发送给 主节点
			 *  4、主节点处理注册，其实就是给这个从节点，分配一个 全局唯一的 ID (从节点启动的时候就已经生成好了)，
			 *  	再通过 ID --> Registrition 注册对象的 这种映射关系保持在 主节点的内存当中。
			 *  5、如果是HBase， 除了 regionserve 上线之后，想 hmaster 注册以外， 还会把自己的信息，写到 ZK 里面
			 */

			/*************************************************
			 *
			 *  注释： 维持心跳 = monitorTarget
			 *  主节点主动！
			 *  1、taskExecutorResourceId
			 *  2、HeartbeatTarget
			 *  针对每一个 TaskEXecutor 都有一个唯一的 HeartbeatTarget 对象！
			 */
			taskManagerHeartbeatManager.monitorTarget(taskExecutorResourceId, new HeartbeatTarget<Void>() {
				@Override
				public void receiveHeartbeat(ResourceID resourceID, Void payload) {
					// TODO_MA 注释： 注意： 意思是说： ResourceManager 会发送心跳消息给 TaskManager
					// the ResourceManager will always send heartbeat requests to the TaskManager
				}

				@Override
				public void requestHeartbeat(ResourceID resourceID, Void payload) {

					/*************************************************
					 *
					 *  注释： RPC 请求
					 *  发送心跳， 回到 TaskExecutor
					 */
					taskExecutorGateway.heartbeatFromResourceManager(resourceID);
				}
			});

			/*************************************************
			 *
			 *  注释： 返回 TaskExecutor 注册成功消息
			 */
			return new TaskExecutorRegistrationSuccess(registration.getInstanceID(), resourceId, clusterInformation);
		}
	}

	private void registerTaskExecutorMetrics() {
		resourceManagerMetricGroup.gauge(MetricNames.NUM_REGISTERED_TASK_MANAGERS, () -> (long) taskExecutors.size());
	}

	private void clearStateInternal() {
		jobManagerRegistrations.clear();
		jmResourceIdRegistrations.clear();
		taskExecutors.clear();

		try {
			jobLeaderIdService.clear();
		} catch(Exception e) {
			onFatalError(new ResourceManagerException("Could not properly clear the job leader id service.", e));
		}
		clearStateFuture = clearStateAsync();
	}

	/**
	 * This method should be called by the framework once it detects that a currently registered
	 * job manager has failed.
	 *
	 * @param jobId identifying the job whose leader shall be disconnected.
	 * @param cause The exception which cause the JobManager failed.
	 */
	protected void closeJobManagerConnection(JobID jobId, Exception cause) {
		JobManagerRegistration jobManagerRegistration = jobManagerRegistrations.remove(jobId);

		if(jobManagerRegistration != null) {
			final ResourceID jobManagerResourceId = jobManagerRegistration.getJobManagerResourceID();
			final JobMasterGateway jobMasterGateway = jobManagerRegistration.getJobManagerGateway();
			final JobMasterId jobMasterId = jobManagerRegistration.getJobMasterId();

			log.info("Disconnect job manager {}@{} for job {} from the resource manager.", jobMasterId, jobMasterGateway.getAddress(), jobId);

			jobManagerHeartbeatManager.unmonitorTarget(jobManagerResourceId);

			jmResourceIdRegistrations.remove(jobManagerResourceId);

			// tell the job manager about the disconnect
			jobMasterGateway.disconnectResourceManager(getFencingToken(), cause);
		} else {
			log.debug("There was no registered job manager for job {}.", jobId);
		}
	}

	/**
	 * This method should be called by the framework once it detects that a currently registered
	 * task executor has failed.
	 *
	 * @param resourceID Id of the TaskManager that has failed.
	 * @param cause      The exception which cause the TaskManager failed.
	 */
	protected void closeTaskManagerConnection(final ResourceID resourceID, final Exception cause) {

		// TODO_MA 注释： 取消
		taskManagerHeartbeatManager.unmonitorTarget(resourceID);

		// TODO_MA 注释： 获取该 TaskManager 的注册信息对象： WorkerRegistration， 同时执行移除
		WorkerRegistration<WorkerType> workerRegistration = taskExecutors.remove(resourceID);

		if(workerRegistration != null) {
			log.info("Closing TaskExecutor connection {} because: {}", resourceID, cause.getMessage());

			/*************************************************
			 *
			 *  注释： 执行 TaskManager 的注销
			 */
			// TODO :: suggest failed task executor to stop itself
			slotManager.unregisterTaskManager(workerRegistration.getInstanceID(), cause);

			/*************************************************
			 *
			 *  注释： 处理 TaskExecutor 的关闭
			 */
			clusterPartitionTracker.processTaskExecutorShutdown(resourceID);

			/*************************************************
			 *
			 *  注释： 该 TaskExecutor 关闭 和 ResourceManager 的链接
			 */
			workerRegistration.getTaskExecutorGateway().disconnectResourceManager(cause);
		} else {
			log.debug("No open TaskExecutor connection {}. Ignoring close TaskExecutor connection. Closing reason was: {}", resourceID,
				cause.getMessage());
		}
	}

	protected void removeJob(JobID jobId) {
		try {
			jobLeaderIdService.removeJob(jobId);
		} catch(Exception e) {
			log.warn("Could not properly remove the job {} from the job leader id service.", jobId, e);
		}

		if(jobManagerRegistrations.containsKey(jobId)) {
			disconnectJobManager(jobId, new Exception("Job " + jobId + "was removed"));
		}
	}

	protected void jobLeaderLostLeadership(JobID jobId, JobMasterId oldJobMasterId) {
		if(jobManagerRegistrations.containsKey(jobId)) {
			JobManagerRegistration jobManagerRegistration = jobManagerRegistrations.get(jobId);

			if(Objects.equals(jobManagerRegistration.getJobMasterId(), oldJobMasterId)) {
				disconnectJobManager(jobId, new Exception("Job leader lost leadership."));
			} else {
				log.debug("Discarding job leader lost leadership, because a new job leader was found for job {}. ", jobId);
			}
		} else {
			log.debug("Discard job leader lost leadership for outdated leader {} for job {}.", oldJobMasterId, jobId);
		}
	}

	protected void releaseResource(InstanceID instanceId, Exception cause) {

		// TODO_MA 注释： 获取到 TaskExecutor 的注册对象
		WorkerType worker = null;
		// TODO: Improve performance by having an index on the instanceId
		for(Map.Entry<ResourceID, WorkerRegistration<WorkerType>> entry : taskExecutors.entrySet()) {
			if(entry.getValue().getInstanceID().equals(instanceId)) {
				worker = entry.getValue().getWorker();
				break;
			}
		}

		if(worker != null) {
			if(stopWorker(worker)) {

				// TODO_MA 注释： 在 YARN 集群中，如果一个 TaskExecutor 处于 idle 状态超过 30s，则释放掉
				closeTaskManagerConnection(worker.getResourceID(), cause);
			} else {

				// TODO_MA 注释： 如果为 StandAlone FLink 集群， 没有释放 TaskExecutor 的必要
				log.debug("Worker {} could not be stopped.", worker.getResourceID());
			}
		} else {
			// TODO_MA 注释： 注销以清理剩余的潜在状态
			// unregister in order to clean up potential left over state
			slotManager.unregisterTaskManager(instanceId, cause);
		}
	}

	// ------------------------------------------------------------------------
	//  Error Handling
	// ------------------------------------------------------------------------

	/**
	 * Notifies the ResourceManager that a fatal error has occurred and it cannot proceed.
	 *
	 * @param t The exception describing the fatal error
	 */
	protected void onFatalError(Throwable t) {
		try {
			log.error("Fatal error occurred in ResourceManager.", t);
		} catch(Throwable ignored) {
		}

		// The fatal error handler implementation should make sure that this call is non-blocking
		fatalErrorHandler.onFatalError(t);
	}

	// ------------------------------------------------------------------------
	//  Leader Contender
	// ------------------------------------------------------------------------

	/**
	 * Callback method when current resourceManager is granted leadership.
	 *
	 * @param newLeaderSessionID unique leadershipID
	 */
	@Override
	public void grantLeadership(final UUID newLeaderSessionID) {

		/*************************************************
		 *
		 *  注释： 调用： tryAcceptLeadership 方法
		 */
		final CompletableFuture<Boolean> acceptLeadershipFuture = clearStateFuture
			.thenComposeAsync((ignored) -> tryAcceptLeadership(newLeaderSessionID), getUnfencedMainThreadExecutor());

		// TODO_MA 注释： 调用 confirmLeadership
		final CompletableFuture<Void> confirmationFuture = acceptLeadershipFuture.thenAcceptAsync((acceptLeadership) -> {
			if(acceptLeadership) {
				// confirming the leader session ID might be blocking,
				leaderElectionService.confirmLeadership(newLeaderSessionID, getAddress());
			}
		}, getRpcService().getExecutor());

		// TODO_MA 注释： 调用 whenComplete
		confirmationFuture.whenComplete((Void ignored, Throwable throwable) -> {
			if(throwable != null) {
				onFatalError(ExceptionUtils.stripCompletionException(throwable));
			}
		});
	}

	private CompletableFuture<Boolean> tryAcceptLeadership(final UUID newLeaderSessionID) {

		// TODO_MA 注释： 判断，如果集群有了 LeaderResourceManager
		if(leaderElectionService.hasLeadership(newLeaderSessionID)) {

			// TODO_MA 注释： 申城一个 ResourceManagerID
			final ResourceManagerId newResourceManagerId = ResourceManagerId.fromUuid(newLeaderSessionID);

			log.info("ResourceManager {} was granted leadership with fencing token {}", getAddress(), newResourceManagerId);

			// TODO_MA 注释： 如果之前已成为过 Leader 的话，则清理之前的状态
			// clear the state if we've been the leader before
			if(getFencingToken() != null) {
				clearStateInternal();
			}
			setFencingToken(newResourceManagerId);

			/*************************************************
			 *
			 *  注释： 启动服务
			 *  1、启动心跳服务
			 *  	启动两个定时任务
			 *  2、启动 SlotManager 服务
			 *  	启动两个定时任务
			 */
			startServicesOnLeadership();

			return prepareLeadershipAsync().thenApply(ignored -> true);
		} else {
			return CompletableFuture.completedFuture(false);
		}
	}

	protected void startServicesOnLeadership() {

		/*************************************************
		 *
		 *  注释： 开启心跳服务
		 */
		startHeartbeatServices();

		/*************************************************
		 *
		 *  注释： 启动 SlotManagerImpl
		 *  这个里面只是开启了两个定时任务而已
		 */
		slotManager.start(getFencingToken(), getMainThreadExecutor(), new ResourceActionsImpl());
	}

	/**
	 * Callback method when current resourceManager loses leadership.
	 */
	@Override
	public void revokeLeadership() {
		runAsyncWithoutFencing(() -> {
			log.info("ResourceManager {} was revoked leadership. Clearing fencing token.", getAddress());

			clearStateInternal();

			setFencingToken(null);

			slotManager.suspend();

			stopHeartbeatServices();

		});
	}

	/*************************************************
	 *
	 *  注释： 当前 ResourceManager  启动了两个心跳服务：
	 *  1、taskManagerHeartbeatManager 这个心跳管理器 关心点的是： taskManager 的死活
	 *  2、jobManagerHeartbeatManager 这个心跳管理器 关心点的是： jobManager 的死活
	 *  taskManager 集群的资源提供者，任务执行者，从节点
	 *  jobManager 每一个job会启动的一个主控程序
	 *  不管是集群的从节点执行心跳，还是每一个job会启动的一个主控程序 都是想 ResourceManager 去汇报
	 *  -
	 *  在 ResourceManager 启动的最后，会启动两个心跳管理器，分别用来接收：
	 *  1、TaskManager 的心跳
	 *  2、JobMaster 的心跳
	 */
	private void startHeartbeatServices() {

		/*************************************************
		 *
		 *  注释： 用来收听： TaskManager 的心跳
		 */
		taskManagerHeartbeatManager = heartbeatServices
			.createHeartbeatManagerSender(resourceId, new TaskManagerHeartbeatListener(), getMainThreadExecutor(), log);

		/*************************************************
		 *
		 *  注释： 用来收听： JobManager 的心跳
		 */
		jobManagerHeartbeatManager = heartbeatServices
			.createHeartbeatManagerSender(resourceId, new JobManagerHeartbeatListener(), getMainThreadExecutor(), log);
	}

	private void stopHeartbeatServices() {
		taskManagerHeartbeatManager.stop();
		jobManagerHeartbeatManager.stop();
	}

	/**
	 * Handles error occurring in the leader election service.
	 *
	 * @param exception Exception being thrown in the leader election service
	 */
	@Override
	public void handleError(final Exception exception) {
		onFatalError(new ResourceManagerException("Received an error from the LeaderElectionService.", exception));
	}

	// ------------------------------------------------------------------------
	//  Framework specific behavior
	// ------------------------------------------------------------------------

	/**
	 * Initializes the framework specific components.
	 *
	 * @throws ResourceManagerException which occurs during initialization and causes the resource manager to fail.
	 */
	protected abstract void initialize() throws ResourceManagerException;

	/**
	 * This method can be overridden to add a (non-blocking) initialization routine to the
	 * ResourceManager that will be called when leadership is granted but before leadership is
	 * confirmed.
	 *
	 * @return Returns a {@code CompletableFuture} that completes when the computation is finished.
	 */
	protected CompletableFuture<Void> prepareLeadershipAsync() {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * This method can be overridden to add a (non-blocking) state clearing routine to the
	 * ResourceManager that will be called when leadership is revoked.
	 *
	 * @return Returns a {@code CompletableFuture} that completes when the state clearing routine
	 * is finished.
	 */
	protected CompletableFuture<Void> clearStateAsync() {
		return CompletableFuture.completedFuture(null);
	}

	/**
	 * The framework specific code to deregister the application. This should report the
	 * application's final status and shut down the resource manager cleanly.
	 *
	 * <p>This method also needs to make sure all pending containers that are not registered
	 * yet are returned.
	 *
	 * @param finalStatus         The application status to report.
	 * @param optionalDiagnostics A diagnostics message or {@code null}.
	 * @throws ResourceManagerException if the application could not be shut down.
	 */
	protected abstract void internalDeregisterApplication(ApplicationStatus finalStatus,
		@Nullable String optionalDiagnostics) throws ResourceManagerException;

	/**
	 * Allocates a resource using the worker resource specification.
	 *
	 * @param workerResourceSpec workerResourceSpec specifies the size of the to be allocated resource
	 * @return whether the resource can be allocated
	 */
	@VisibleForTesting
	public abstract boolean startNewWorker(WorkerResourceSpec workerResourceSpec);

	/**
	 * Callback when a worker was started.
	 *
	 * @param resourceID The worker resource id
	 */
	protected abstract WorkerType workerStarted(ResourceID resourceID);

	/**
	 * Stops the given worker.
	 *
	 * @param worker The worker.
	 * @return True if the worker was stopped, otherwise false
	 */
	public abstract boolean stopWorker(WorkerType worker);

	/**
	 * Set {@link SlotManager} whether to fail unfulfillable slot requests.
	 *
	 * @param failUnfulfillableRequest whether to fail unfulfillable requests
	 */
	protected void setFailUnfulfillableRequest(boolean failUnfulfillableRequest) {
		slotManager.setFailUnfulfillableRequest(failUnfulfillableRequest);
	}

	// ------------------------------------------------------------------------
	//  Static utility classes
	// ------------------------------------------------------------------------

	private class ResourceActionsImpl implements ResourceActions {

		@Override
		public void releaseResource(InstanceID instanceId, Exception cause) {
			validateRunsInMainThread();

			/*************************************************
			 *
			 *  注释： 释放资源
			 */
			ResourceManager.this.releaseResource(instanceId, cause);
		}

		@Override
		public boolean allocateResource(WorkerResourceSpec workerResourceSpec) {
			validateRunsInMainThread();
			/*************************************************
			 *
			 *  注释： 开启新的 TaskExecutor， 如果是 YARN 的会去做，如果是 StandAlone 的话就没法了
			 */
			return startNewWorker(workerResourceSpec);
		}

		@Override
		public void notifyAllocationFailure(JobID jobId, AllocationID allocationId, Exception cause) {
			validateRunsInMainThread();

			JobManagerRegistration jobManagerRegistration = jobManagerRegistrations.get(jobId);
			if(jobManagerRegistration != null) {

				/*************************************************
				 *
				 *  注释： ResourceManager 告知 JobMaster
				 */
				jobManagerRegistration.getJobManagerGateway().notifyAllocationFailure(allocationId, cause);
			}
		}
	}

	private class JobLeaderIdActionsImpl implements JobLeaderIdActions {

		@Override
		public void jobLeaderLostLeadership(final JobID jobId, final JobMasterId oldJobMasterId) {
			runAsync(new Runnable() {
				@Override
				public void run() {
					ResourceManager.this.jobLeaderLostLeadership(jobId, oldJobMasterId);
				}
			});
		}

		@Override
		public void notifyJobTimeout(final JobID jobId, final UUID timeoutId) {
			runAsync(new Runnable() {
				@Override
				public void run() {
					if(jobLeaderIdService.isValidTimeout(jobId, timeoutId)) {
						removeJob(jobId);
					}
				}
			});
		}

		@Override
		public void handleError(Throwable error) {
			onFatalError(error);
		}
	}

	private class TaskManagerHeartbeatListener implements HeartbeatListener<TaskExecutorHeartbeatPayload, Void> {

		@Override
		public void notifyHeartbeatTimeout(final ResourceID resourceID) {
			validateRunsInMainThread();
			log.info("The heartbeat of TaskManager with id {} timed out.", resourceID);

			/*************************************************
			 *
			 *  注释： 关闭和 TaskManager 的链接
			 */
			closeTaskManagerConnection(resourceID, new TimeoutException("The heartbeat of TaskManager with id " + resourceID + "  timed out."));
		}

		@Override
		public void reportPayload(final ResourceID resourceID, final TaskExecutorHeartbeatPayload payload) {
			validateRunsInMainThread();
			final WorkerRegistration<WorkerType> workerRegistration = taskExecutors.get(resourceID);

			if(workerRegistration == null) {
				log.debug("Received slot report from TaskManager {} which is no longer registered.", resourceID);
			} else {
				InstanceID instanceId = workerRegistration.getInstanceID();

				slotManager.reportSlotStatus(instanceId, payload.getSlotReport());
				clusterPartitionTracker.processTaskExecutorClusterPartitionReport(resourceID, payload.getClusterPartitionReport());
			}
		}

		@Override
		public Void retrievePayload(ResourceID resourceID) {
			return null;
		}
	}

	private class JobManagerHeartbeatListener implements HeartbeatListener<Void, Void> {

		@Override
		public void notifyHeartbeatTimeout(final ResourceID resourceID) {
			validateRunsInMainThread();
			log.info("The heartbeat of JobManager with id {} timed out.", resourceID);

			if(jmResourceIdRegistrations.containsKey(resourceID)) {
				JobManagerRegistration jobManagerRegistration = jmResourceIdRegistrations.get(resourceID);

				if(jobManagerRegistration != null) {
					closeJobManagerConnection(jobManagerRegistration.getJobID(),
						new TimeoutException("The heartbeat of JobManager with id " + resourceID + " timed out."));
				}
			}
		}

		@Override
		public void reportPayload(ResourceID resourceID, Void payload) {
			// nothing to do since there is no payload
		}

		@Override
		public Void retrievePayload(ResourceID resourceID) {
			return null;
		}
	}

	// ------------------------------------------------------------------------
	//  Resource Management
	// ------------------------------------------------------------------------

	protected int getNumberRequiredTaskManagers() {
		return getRequiredResources().values().stream().reduce(0, Integer::sum);
	}

	protected Map<WorkerResourceSpec, Integer> getRequiredResources() {
		return slotManager.getRequiredResources();
	}
}

