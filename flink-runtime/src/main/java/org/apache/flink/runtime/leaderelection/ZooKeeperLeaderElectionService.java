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

package org.apache.flink.runtime.leaderelection;

import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import org.apache.flink.shaded.curator4.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.leader.LeaderLatch;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.leader.LeaderLatchListener;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.state.ConnectionState;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.state.ConnectionStateListener;
import org.apache.flink.shaded.zookeeper3.org.apache.zookeeper.CreateMode;
import org.apache.flink.shaded.zookeeper3.org.apache.zookeeper.KeeperException;
import org.apache.flink.shaded.zookeeper3.org.apache.zookeeper.data.Stat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.Nonnull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

/**
 * // TODO_MA 注释： 多个 JobManager 的领导者选举服务。使用 ZooKeeper 选择领先的 JobManager。
 * Leader election service for multiple JobManager. The leading JobManager is elected using ZooKeeper.
 *
 * // TODO_MA 注释： 当前领导者的地址及其领导者会话 ID 也会通过 ZooKeeper 发布。
 * The current leader's address as well as its leader session ID is published via ZooKeeper as well.
 */
public class ZooKeeperLeaderElectionService implements LeaderElectionService, LeaderLatchListener, NodeCacheListener, UnhandledErrorListener {

	private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperLeaderElectionService.class);

	private final Object lock = new Object();

	/**
	 * Client to the ZooKeeper quorum.
	 */
	private final CuratorFramework client;

	/**
	 * Curator recipe for leader election.
	 */
	private final LeaderLatch leaderLatch;

	/**
	 * Curator recipe to watch a given ZooKeeper node for changes.
	 */
	private final NodeCache cache;

	/**
	 * ZooKeeper path of the node which stores the current leader information.
	 */
	private final String leaderPath;

	private volatile UUID issuedLeaderSessionID;

	private volatile UUID confirmedLeaderSessionID;

	private volatile String confirmedLeaderAddress;

	/**
	 * The leader contender which applies for leadership.
	 */
	private volatile LeaderContender leaderContender;

	private volatile boolean running;

	private final ConnectionStateListener listener = new ConnectionStateListener() {
		@Override
		public void stateChanged(CuratorFramework client, ConnectionState newState) {

			/*************************************************
			 *
			 *  注释： 执行处理
			 */
			handleStateChange(newState);
		}
	};

	/**
	 * Creates a ZooKeeperLeaderElectionService object.
	 *
	 * @param client     Client which is connected to the ZooKeeper quorum
	 * @param latchPath  ZooKeeper node path for the leader election latch
	 * @param leaderPath ZooKeeper node path for the node which stores the current leader information
	 */
	public ZooKeeperLeaderElectionService(CuratorFramework client, String latchPath, String leaderPath) {
		this.client = Preconditions.checkNotNull(client, "CuratorFramework client");
		this.leaderPath = Preconditions.checkNotNull(leaderPath, "leaderPath");

		leaderLatch = new LeaderLatch(client, latchPath);
		cache = new NodeCache(client, leaderPath);

		issuedLeaderSessionID = null;
		confirmedLeaderSessionID = null;
		confirmedLeaderAddress = null;
		leaderContender = null;

		running = false;
	}

	/**
	 * Returns the current leader session ID or null, if the contender is not the leader.
	 *
	 * @return The last leader session ID or null, if the contender is not the leader
	 */
	public UUID getLeaderSessionID() {
		return confirmedLeaderSessionID;
	}

	/*************************************************
	 *
	 *  注释： 注意这个方法，因为当前这个类是 LeaderLatchListener 的子类，所以当该组件在进行选举如果成功的话
	 *  则会自动调用 isLeader() 方法，否则调用 notLeader 方法。
	 *  这是 ZooKeeper 的 API 框架 cruator 的机制
	 */
	@Override
	public void start(LeaderContender contender) throws Exception {
		Preconditions.checkNotNull(contender, "Contender must not be null.");
		Preconditions.checkState(leaderContender == null, "Contender was already set.");

		LOG.info("Starting ZooKeeperLeaderElectionService {}.", this);

		synchronized(lock) {

			client.getUnhandledErrorListenable().addListener(this);

			// TODO_MA 注释： 这个值到底是什么，根据情况而定
			leaderContender = contender;

			/*************************************************
			 *
			 *  注释： Fink 的 选举，和 HBase 一样都是通过 ZooKeeper 的 API 框架 Curator 实现的
			 *  1、leaderLatch.start(); 事实上就是举行选举
			 *  2、当选举结束的时候：
			 *  	如果成功了： isLeader()
			 *      如果失败了： notLeader()
			 */
			leaderLatch.addListener(this);
			leaderLatch.start();

			/*************************************************
			 *
			 *  注释： 注册监听器，如果选举结束之后：
			 *  1、自己成为 Leader, 则会回调 isLeader() 进行处理
			 *  2、自己成为 Follower，则会回调 notLeader() 进行处理
			 */
			cache.getListenable().addListener(this);
			cache.start();

			client.getConnectionStateListenable().addListener(listener);

			running = true;
		}
	}

	@Override
	public void stop() throws Exception {
		synchronized(lock) {
			if(!running) {
				return;
			}

			running = false;
			confirmedLeaderSessionID = null;
			issuedLeaderSessionID = null;
		}

		LOG.info("Stopping ZooKeeperLeaderElectionService {}.", this);

		client.getUnhandledErrorListenable().removeListener(this);

		client.getConnectionStateListenable().removeListener(listener);

		Exception exception = null;

		try {
			cache.close();
		} catch(Exception e) {
			exception = ExceptionUtils.firstOrSuppressed(e, exception);
		}

		try {
			leaderLatch.close();
		} catch(Exception e) {
			exception = ExceptionUtils.firstOrSuppressed(e, exception);
		}

		if(exception != null) {
			throw new Exception("Could not properly stop the ZooKeeperLeaderElectionService.", exception);
		}
	}

	@Override
	public void confirmLeadership(UUID leaderSessionID, String leaderAddress) {
		if(LOG.isDebugEnabled()) {
			LOG.debug("Confirm leader session ID {} for leader {}.", leaderSessionID, leaderAddress);
		}

		Preconditions.checkNotNull(leaderSessionID);

		if(leaderLatch.hasLeadership()) {
			// check if this is an old confirmation call
			synchronized(lock) {
				if(running) {
					if(leaderSessionID.equals(this.issuedLeaderSessionID)) {

						/*************************************************
						 *
						 *  注释： 获取 Leader 信息
						 */
						confirmLeaderInformation(leaderSessionID, leaderAddress);
						writeLeaderInformation();
					}
				} else {
					LOG.debug(
						"Ignoring the leader session Id {} confirmation, since the " + "ZooKeeperLeaderElectionService has already been stopped.",
						leaderSessionID);
				}
			}
		} else {
			LOG.warn("The leader session ID {} was confirmed even though the " + "corresponding JobManager was not elected as the leader.",
				leaderSessionID);
		}
	}

	private void confirmLeaderInformation(UUID leaderSessionID, String leaderAddress) {
		confirmedLeaderSessionID = leaderSessionID;
		confirmedLeaderAddress = leaderAddress;
	}

	@Override
	public boolean hasLeadership(@Nonnull UUID leaderSessionId) {
		return leaderLatch.hasLeadership() && leaderSessionId.equals(issuedLeaderSessionID);
	}

	@Override
	public void isLeader() {
		synchronized(lock) {
			if(running) {
				issuedLeaderSessionID = UUID.randomUUID();
				clearConfirmedLeaderInformation();

				if(LOG.isDebugEnabled()) {
					LOG.debug("Grant leadership to contender {} with session ID {}.", leaderContender.getDescription(), issuedLeaderSessionID);
				}

				/*************************************************
				 *
				 *  注释： 分配 LeaderShip
				 *  leaderContender = JobManagerRunnerImpl
				 *  leaderContender = ResourceManager
				 *  leaderContender = DefaultDispatcherRunner
				 *  leaderContender = WebMonitorEndpoint
				 *
				 *  leaderElectionService.start(this);
				 *  leaderContender = this
				 */
				leaderContender.grantLeadership(issuedLeaderSessionID);

			} else {
				LOG.debug("Ignoring the grant leadership notification since the service has " + "already been stopped.");
			}
		}
	}

	private void clearConfirmedLeaderInformation() {
		confirmedLeaderSessionID = null;
		confirmedLeaderAddress = null;
	}

	@Override
	public void notLeader() {
		synchronized(lock) {
			if(running) {
				LOG.debug("Revoke leadership of {} ({}@{}).", leaderContender.getDescription(), confirmedLeaderSessionID, confirmedLeaderAddress);

				issuedLeaderSessionID = null;
				clearConfirmedLeaderInformation();

				leaderContender.revokeLeadership();
			} else {
				LOG.debug("Ignoring the revoke leadership notification since the service " + "has already been stopped.");
			}
		}
	}

	@Override
	public void nodeChanged() throws Exception {
		try {
			// leaderSessionID is null if the leader contender has not yet confirmed the session ID
			if(leaderLatch.hasLeadership()) {
				synchronized(lock) {
					if(running) {
						if(LOG.isDebugEnabled()) {
							LOG.debug("Leader node changed while {} is the leader with session ID {}.", leaderContender.getDescription(),
								confirmedLeaderSessionID);
						}

						if(confirmedLeaderSessionID != null) {
							ChildData childData = cache.getCurrentData();

							if(childData == null) {
								if(LOG.isDebugEnabled()) {
									LOG.debug("Writing leader information into empty node by {}.", leaderContender.getDescription());
								}
								writeLeaderInformation();
							} else {
								byte[] data = childData.getData();

								if(data == null || data.length == 0) {
									// the data field seems to be empty, rewrite information
									if(LOG.isDebugEnabled()) {
										LOG.debug("Writing leader information into node with empty data field by {}.",
											leaderContender.getDescription());
									}
									writeLeaderInformation();
								} else {
									ByteArrayInputStream bais = new ByteArrayInputStream(data);
									ObjectInputStream ois = new ObjectInputStream(bais);

									String leaderAddress = ois.readUTF();
									UUID leaderSessionID = (UUID) ois.readObject();

									if(!leaderAddress.equals(confirmedLeaderAddress) || (leaderSessionID == null || !leaderSessionID
										.equals(confirmedLeaderSessionID))) {
										// the data field does not correspond to the expected leader information
										if(LOG.isDebugEnabled()) {
											LOG.debug("Correcting leader information by {}.", leaderContender.getDescription());
										}
										writeLeaderInformation();
									}
								}
							}
						}
					} else {
						LOG.debug("Ignoring node change notification since the service has already been stopped.");
					}
				}
			}
		} catch(Exception e) {
			leaderContender.handleError(new Exception("Could not handle node changed event.", e));
			throw e;
		}
	}

	/**
	 * Writes the current leader's address as well the given leader session ID to ZooKeeper.
	 */
	protected void writeLeaderInformation() {
		// this method does not have to be synchronized because the curator framework client
		// is thread-safe
		try {
			if(LOG.isDebugEnabled()) {
				LOG.debug("Write leader information: Leader={}, session ID={}.", confirmedLeaderAddress, confirmedLeaderSessionID);
			}
			ByteArrayOutputStream baos = new ByteArrayOutputStream();
			ObjectOutputStream oos = new ObjectOutputStream(baos);

			oos.writeUTF(confirmedLeaderAddress);
			oos.writeObject(confirmedLeaderSessionID);

			oos.close();

			boolean dataWritten = false;

			while(!dataWritten && leaderLatch.hasLeadership()) {
				Stat stat = client.checkExists().forPath(leaderPath);

				if(stat != null) {
					long owner = stat.getEphemeralOwner();
					long sessionID = client.getZookeeperClient().getZooKeeper().getSessionId();

					if(owner == sessionID) {
						try {
							client.setData().forPath(leaderPath, baos.toByteArray());

							dataWritten = true;
						} catch(KeeperException.NoNodeException noNode) {
							// node was deleted in the meantime
						}
					} else {
						try {
							client.delete().forPath(leaderPath);
						} catch(KeeperException.NoNodeException noNode) {
							// node was deleted in the meantime --> try again
						}
					}
				} else {
					try {
						client.create().creatingParentsIfNeeded().withMode(CreateMode.EPHEMERAL).forPath(leaderPath, baos.toByteArray());

						dataWritten = true;
					} catch(KeeperException.NodeExistsException nodeExists) {
						// node has been created in the meantime --> try again
					}
				}
			}

			if(LOG.isDebugEnabled()) {
				LOG.debug("Successfully wrote leader information: Leader={}, session ID={}.", confirmedLeaderAddress, confirmedLeaderSessionID);
			}
		} catch(Exception e) {
			leaderContender.handleError(new Exception("Could not write leader address and leader session ID to " + "ZooKeeper.", e));
		}
	}

	protected void handleStateChange(ConnectionState newState) {
		switch(newState) {
			case CONNECTED:
				LOG.debug("Connected to ZooKeeper quorum. Leader election can start.");
				break;
			case SUSPENDED:
				LOG.warn("Connection to ZooKeeper suspended. The contender " + leaderContender
					.getDescription() + " no longer participates in the leader election.");
				break;
			case RECONNECTED:
				LOG.info("Connection to ZooKeeper was reconnected. Leader election can be restarted.");
				break;
			case LOST:
				// Maybe we have to throw an exception here to terminate the JobManager
				LOG.warn("Connection to ZooKeeper lost. The contender " + leaderContender
					.getDescription() + " no longer participates in the leader election.");
				break;
		}
	}

	@Override
	public void unhandledError(String message, Throwable e) {
		leaderContender.handleError(new FlinkException("Unhandled error in ZooKeeperLeaderElectionService: " + message, e));
	}

	@Override
	public String toString() {
		return "ZooKeeperLeaderElectionService{" + "leaderPath='" + leaderPath + '\'' + '}';
	}
}
