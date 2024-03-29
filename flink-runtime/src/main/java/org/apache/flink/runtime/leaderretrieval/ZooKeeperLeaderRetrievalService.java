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

package org.apache.flink.runtime.leaderretrieval;

import org.apache.flink.util.FlinkException;
import org.apache.flink.util.Preconditions;

import org.apache.flink.shaded.curator4.org.apache.curator.framework.CuratorFramework;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.api.UnhandledErrorListener;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.ChildData;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.NodeCache;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.recipes.cache.NodeCacheListener;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.state.ConnectionState;
import org.apache.flink.shaded.curator4.org.apache.curator.framework.state.ConnectionStateListener;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.concurrent.GuardedBy;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.util.Objects;
import java.util.UUID;

/**
 * The counterpart to the {@link org.apache.flink.runtime.leaderelection.ZooKeeperLeaderElectionService}.
 * This implementation of the {@link LeaderRetrievalService} retrieves the current leader which has
 * been elected by the {@link org.apache.flink.runtime.leaderelection.ZooKeeperLeaderElectionService}.
 * The leader address as well as the current leader session ID is retrieved from ZooKeeper.
 */
public class ZooKeeperLeaderRetrievalService implements LeaderRetrievalService, NodeCacheListener, UnhandledErrorListener {
	private static final Logger LOG = LoggerFactory.getLogger(ZooKeeperLeaderRetrievalService.class);

	private final Object lock = new Object();

	/**
	 * Connection to the used ZooKeeper quorum.
	 */
	private final CuratorFramework client;

	/**
	 * Curator recipe to watch changes of a specific ZooKeeper node.
	 */
	private final NodeCache cache;

	private final String retrievalPath;

	/**
	 * Listener which will be notified about leader changes.
	 */
	private volatile LeaderRetrievalListener leaderListener;

	private String lastLeaderAddress;

	private UUID lastLeaderSessionID;

	private volatile boolean running;

	private final ConnectionStateListener connectionStateListener = new ConnectionStateListener() {
		@Override
		public void stateChanged(CuratorFramework client, ConnectionState newState) {
			handleStateChange(newState);
		}
	};

	/**
	 * Creates a leader retrieval service which uses ZooKeeper to retrieve the leader information.
	 *
	 * @param client        Client which constitutes the connection to the ZooKeeper quorum
	 * @param retrievalPath Path of the ZooKeeper node which contains the leader information
	 */
	public ZooKeeperLeaderRetrievalService(CuratorFramework client, String retrievalPath) {
		this.client = Preconditions.checkNotNull(client, "CuratorFramework client");
		this.cache = new NodeCache(client, retrievalPath);
		this.retrievalPath = Preconditions.checkNotNull(retrievalPath);

		this.leaderListener = null;
		this.lastLeaderAddress = null;
		this.lastLeaderSessionID = null;

		running = false;
	}

	/*************************************************
	 *
	 *  注释： 监听服务
	 *  -
	 *  在使用 ZK 的时候，一般可能这么写代码：
	 *  -
	 *  Client1: ZooKeeper zk = new ZooKeeper(connetString, timeout, null)；
	 *  zk.getData(znodePath, watcher);
	 *  -
	 *  Client2： zk.setData(znodePath, newData);
	 *  -
	 *  当 Client2 执行代码之后， CLient1 机会收到 ZK 系统发送过来的响应事件： NodeDataChanged
	 *  存在于 zk 实例内部的 WatcherManager就回去回调　watcher.process(WatchedEvent event)
	 *  -
	 *  LeaderRetrievalListener 就等同于 Watcher
	 *  start() 方法等同于启动监听
	 */
	@Override
	public void start(LeaderRetrievalListener listener) throws Exception {
		Preconditions.checkNotNull(listener, "Listener must not be null.");
		Preconditions.checkState(leaderListener == null, "ZooKeeperLeaderRetrievalService can " + "only be started once.");

		LOG.info("Starting ZooKeeperLeaderRetrievalService {}.", retrievalPath);

		/*************************************************
		 *
		 *  注释： 监听 Leader 的变更， 如果变更发生，则对应的监听器，收到通知
		 */
		synchronized(lock) {
			leaderListener = listener;
			client.getUnhandledErrorListenable().addListener(this);
			cache.getListenable().addListener(this);
			cache.start();
			client.getConnectionStateListenable().addListener(connectionStateListener);
			running = true;
		}
	}

	@Override
	public void stop() throws Exception {
		LOG.info("Stopping ZooKeeperLeaderRetrievalService {}.", retrievalPath);

		synchronized(lock) {
			if(!running) {
				return;
			}

			running = false;
		}

		client.getUnhandledErrorListenable().removeListener(this);
		client.getConnectionStateListenable().removeListener(connectionStateListener);

		try {
			cache.close();
		} catch(IOException e) {
			throw new Exception("Could not properly stop the ZooKeeperLeaderRetrievalService.", e);
		}
	}

	/*************************************************
	 *
	 *  注释： 当监听响应的时候，就会自动调用 这个方法，等同于 zk 中的 watcher 中的 process 方法
	 *  这是属于 curator 的知识
	 *  如果 ResourceManager 发生变更过， 或者你是第一次启动
	 */
	@Override
	public void nodeChanged() throws Exception {
		synchronized(lock) {
			if(running) {
				try {
					LOG.debug("Leader node has changed.");

					ChildData childData = cache.getCurrentData();

					String leaderAddress;
					UUID leaderSessionID;

					if(childData == null) {
						leaderAddress = null;
						leaderSessionID = null;
					} else {
						byte[] data = childData.getData();

						if(data == null || data.length == 0) {
							leaderAddress = null;
							leaderSessionID = null;
						} else {
							ByteArrayInputStream bais = new ByteArrayInputStream(data);
							ObjectInputStream ois = new ObjectInputStream(bais);

							leaderAddress = ois.readUTF();
							leaderSessionID = (UUID) ois.readObject();
						}
					}

					/*************************************************
					 *
					 *  注释： 通过 RM 的变更
					 *  leaderAddress ResourceManager 的地址：
					 */
					notifyIfNewLeaderAddress(leaderAddress, leaderSessionID);

				} catch(Exception e) {
					leaderListener.handleError(new Exception("Could not handle node changed event.", e));
					throw e;
				}
			} else {
				LOG.debug("Ignoring node change notification since the service has already been stopped.");
			}
		}
	}

	protected void handleStateChange(ConnectionState newState) {
		switch(newState) {
			case CONNECTED:
				LOG.debug("Connected to ZooKeeper quorum. Leader retrieval can start.");
				break;
			case SUSPENDED:
				LOG.warn("Connection to ZooKeeper suspended. Can no longer retrieve the leader from " + "ZooKeeper.");
				synchronized(lock) {
					notifyLeaderLoss();
				}
				break;
			case RECONNECTED:
				LOG.info("Connection to ZooKeeper was reconnected. Leader retrieval can be restarted.");
				break;
			case LOST:
				LOG.warn("Connection to ZooKeeper lost. Can no longer retrieve the leader from " + "ZooKeeper.");
				synchronized(lock) {
					notifyLeaderLoss();
				}
				break;
		}
	}

	@Override
	public void unhandledError(String s, Throwable throwable) {
		leaderListener.handleError(new FlinkException("Unhandled error in ZooKeeperLeaderRetrievalService:" + s, throwable));
	}

	@GuardedBy("lock")
	private void notifyIfNewLeaderAddress(String newLeaderAddress, UUID newLeaderSessionID) {
		if(!(Objects.equals(newLeaderAddress, lastLeaderAddress) && Objects.equals(newLeaderSessionID, lastLeaderSessionID))) {
			if(newLeaderAddress == null && newLeaderSessionID == null) {
				LOG.debug("Leader information was lost: The listener will be notified accordingly.");
			} else {
				LOG.debug("New leader information: Leader={}, session ID={}.", newLeaderAddress, newLeaderSessionID);
			}

			lastLeaderAddress = newLeaderAddress;
			lastLeaderSessionID = newLeaderSessionID;

			/*************************************************
			 *
			 *  注释： 通知 RM 的变更
			 *  leaderListener = ResourceManagerLeaderLisnter
			 */
			leaderListener.notifyLeaderAddress(newLeaderAddress, newLeaderSessionID);
		}
	}

	@GuardedBy("lock")
	private void notifyLeaderLoss() {
		notifyIfNewLeaderAddress(null, null);
	}
}
