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

package org.apache.flink.streaming.runtime.tasks.mailbox;

import org.apache.flink.annotation.Internal;
import org.apache.flink.annotation.VisibleForTesting;
import org.apache.flink.metrics.Meter;
import org.apache.flink.metrics.MeterView;
import org.apache.flink.metrics.SimpleCounter;
import org.apache.flink.runtime.metrics.groups.TaskMetricGroup;
import org.apache.flink.streaming.api.operators.MailboxExecutor;
import org.apache.flink.streaming.runtime.tasks.StreamTaskActionExecutor;
import org.apache.flink.util.ExceptionUtils;
import org.apache.flink.util.Preconditions;
import org.apache.flink.util.WrappingRuntimeException;
import org.apache.flink.util.function.RunnableWithException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.util.List;
import java.util.Optional;

import static org.apache.flink.streaming.runtime.tasks.mailbox.TaskMailbox.MIN_PRIORITY;

/**
 * This class encapsulates the logic of the mailbox-based execution model. At the core of this model {@link
 * #runMailboxLoop()} that continuously executes the provided {@link MailboxDefaultAction} in a loop. On each iteration,
 * the method also checks if there are pending actions in the mailbox and executes such actions. This model ensures
 * single-threaded execution between the default action (e.g. record processing) and mailbox actions (e.g. checkpoint
 * trigger, timer firing, ...).
 *
 * <p>The {@link MailboxDefaultAction} interacts with this class through the {@link MailboxController} to
 * communicate control flow changes to the mailbox loop, e.g. that invocations of the default action are temporarily or
 * permanently exhausted.
 *
 * <p>The design of {@link #runMailboxLoop()} is centered around the idea of keeping the expected hot path
 * (default action, no mail) as fast as possible. This means that all checking of mail and other control flags
 * (mailboxLoopRunning, suspendedDefaultAction) are always connected to #hasMail indicating true. This means that
 * control flag changes in the mailbox thread can be done directly, but we must ensure that there is at least one action
 * in the mailbox so that the change is picked up. For control flag changes by all other threads, that must happen
 * through mailbox actions, this is automatically the case.
 *
 * <p>This class has a open-prepareClose-close lifecycle that is connected with and maps to the lifecycle of the
 * encapsulated {@link TaskMailbox} (which is open-quiesce-close).
 */
@Internal
public class MailboxProcessor implements Closeable {

	private static final Logger LOG = LoggerFactory.getLogger(MailboxProcessor.class);

	/**
	 * // TODO_MA 注释： 负责存储相应 task 任务（也就是 mail），它支持多写单读，单线程读取并处理
	 * // TODO_MA 注释： 邮箱数据结构，用于管理对特殊操作的请求，例如计时器，检查点，...
	 * The mailbox data-structure that manages request for special actions, like timers, checkpoints, ...
	 */
	protected final TaskMailbox mailbox;

	/**
	 * Action that is repeatedly executed if no action request is in the mailbox. Typically record processing.
	 */
	protected final MailboxDefaultAction mailboxDefaultAction;

	/**
	 * Control flag to terminate the mailbox loop. Must only be accessed from mailbox thread.
	 */
	private boolean mailboxLoopRunning;

	/**
	 * Remembers a currently active suspension of the default action. Serves as flag to indicate a suspended
	 * default action (suspended if not-null) and to reuse the object as return value in consecutive suspend attempts.
	 * Must only be accessed from mailbox thread.
	 */
	private MailboxDefaultAction.Suspension suspendedDefaultAction;

	private final StreamTaskActionExecutor actionExecutor;

	private Meter idleTime = new MeterView(new SimpleCounter());

	public MailboxProcessor(MailboxDefaultAction mailboxDefaultAction) {

		/*************************************************
		 * 
		 *  注释：
		 *  第三个参数的初始化：StreamTaskActionExecutor
		 */
		this(mailboxDefaultAction, StreamTaskActionExecutor.IMMEDIATE);
	}

	public MailboxProcessor(MailboxDefaultAction mailboxDefaultAction, StreamTaskActionExecutor actionExecutor) {

		/*************************************************
		 * 
		 *  注释：
		 *  第二个参数的初始化
		 */
		this(mailboxDefaultAction, new TaskMailboxImpl(Thread.currentThread()), actionExecutor);
	}

	public MailboxProcessor(MailboxDefaultAction mailboxDefaultAction, TaskMailbox mailbox, StreamTaskActionExecutor actionExecutor) {
		this.mailboxDefaultAction = Preconditions.checkNotNull(mailboxDefaultAction);
		this.actionExecutor = Preconditions.checkNotNull(actionExecutor);

		// TODO_MA 注释： TaskMailBoxImpl
		this.mailbox = Preconditions.checkNotNull(mailbox);
		this.mailboxLoopRunning = true;
		this.suspendedDefaultAction = null;
	}

	public MailboxExecutor getMainMailboxExecutor() {

		/*************************************************
		 * 
		 *  注释： 返回 MailboxExecutorImpl
		 */
		return new MailboxExecutorImpl(mailbox, MIN_PRIORITY, actionExecutor);
	}

	/**
	 * Returns an executor service facade to submit actions to the mailbox.
	 *
	 * @param priority the priority of the {@link MailboxExecutor}.
	 */
	public MailboxExecutor getMailboxExecutor(int priority) {
		return new MailboxExecutorImpl(mailbox, priority, actionExecutor, this);
	}

	public void initMetric(TaskMetricGroup metricGroup) {
		idleTime = metricGroup.getIOMetricGroup().getIdleTimeMsPerSecond();
	}

	/**
	 * Lifecycle method to close the mailbox for action submission.
	 */
	public void prepareClose() {
		mailbox.quiesce();
	}

	/**
	 * Lifecycle method to close the mailbox for action submission/retrieval. This will cancel all instances of
	 * {@link java.util.concurrent.RunnableFuture} that are still contained in the mailbox.
	 */
	@Override
	public void close() {
		List<Mail> droppedMails = mailbox.close();
		if(!droppedMails.isEmpty()) {
			LOG.debug("Closing the mailbox dropped mails {}.", droppedMails);
			Optional<RuntimeException> maybeErr = Optional.empty();
			for(Mail droppedMail : droppedMails) {
				try {
					droppedMail.tryCancel(false);
				} catch(RuntimeException x) {
					maybeErr = Optional.of(ExceptionUtils.firstOrSuppressed(x, maybeErr.orElse(null)));
				}
			}
			maybeErr.ifPresent(e -> {
				throw e;
			});
		}
	}

	/**
	 * Finishes running all mails in the mailbox. If no concurrent write operations occurred, the mailbox must be
	 * empty after this method.
	 */
	public void drain() throws Exception {
		for(final Mail mail : mailbox.drain()) {
			mail.run();
		}
	}

	/**
	 * Runs the mailbox processing loop. This is where the main work is done.
	 */
	public void runMailboxLoop() throws Exception {

		final TaskMailbox localMailbox = mailbox;

		/*************************************************
		 * 
		 *  注释： Mailbox 的线程检查 和 状态检查
		 */
		Preconditions.checkState(localMailbox.isMailboxThread(), "Method must be executed by declared mailbox thread!");
		assert localMailbox.getState() == TaskMailbox.State.OPEN : "Mailbox must be opened!";

		final MailboxController defaultActionContext = new MailboxController(this);

		/*************************************************
		 * 
		 *  注释： 执行 mailbox 处理，正式工作
		 */
		while(runMailboxStep(localMailbox, defaultActionContext)) {
		}

		// TODO_MA 注释： 代码卡在这儿
	}

	public boolean runMailboxStep() throws Exception {
		return runMailboxStep(mailbox, new MailboxController(this));
	}

	private boolean runMailboxStep(TaskMailbox localMailbox, MailboxController defaultActionContext) throws Exception {

		/*************************************************
		 * 
		 *  注释： 处理 Mail
		 *  1、如果有 mail 需要处理，这里会进行相应的处理，处理完才会进行下面的 event processing
		 *  2、进行 task 的 default action，也就是调用 processInput()
		 */
		if(processMail(localMailbox)) {
			mailboxDefaultAction.runDefaultAction(defaultActionContext); // lock is acquired inside default action as needed
			return true;
		}
		return false;
	}

	/**
	 * Check if the current thread is the mailbox thread.
	 *
	 * @return only true if called from the mailbox thread.
	 */
	public boolean isMailboxThread() {
		return mailbox.isMailboxThread();
	}

	/**
	 * Reports a throwable for rethrowing from the mailbox thread. This will clear and cancel all other pending mails.
	 *
	 * @param throwable to report by rethrowing from the mailbox loop.
	 */
	public void reportThrowable(Throwable throwable) {
		sendControlMail(() -> {
			if(throwable instanceof Exception) {
				throw (Exception) throwable;
			} else if(throwable instanceof Error) {
				throw (Error) throwable;
			} else {
				throw WrappingRuntimeException.wrapIfNecessary(throwable);
			}
		}, "Report throwable %s", throwable);
	}

	/**
	 * This method must be called to end the stream task when all actions for the tasks have been performed.
	 */
	public void allActionsCompleted() {
		mailbox.runExclusively(() -> {
			// keep state check and poison mail enqueuing atomic, such that no intermediate #close may cause a
			// MailboxStateException in #sendPriorityMail.
			if(mailbox.getState() == TaskMailbox.State.OPEN) {
				sendControlMail(() -> mailboxLoopRunning = false, "poison mail");
			}
		});
	}

	/**
	 * Sends the given <code>mail</code> using {@link TaskMailbox#putFirst(Mail)} .
	 * Intended use is to control this <code>MailboxProcessor</code>; no interaction with tasks should be performed;
	 */
	private void sendControlMail(RunnableWithException mail, String descriptionFormat, Object... descriptionArgs) {

		/*************************************************
		 * 
		 *  注释： controlMail 都是最先被处理的
		 */
		mailbox.putFirst(new Mail(mail, Integer.MAX_VALUE /*not used with putFirst*/, descriptionFormat, descriptionArgs));
	}

	/**
	 * This helper method handles all special actions from the mailbox. It returns true if the mailbox loop should
	 * continue running, false if it should stop. In the current design, this method also evaluates all control flag
	 * changes. This keeps the hot path in {@link #runMailboxLoop()} free from any other flag checking, at the cost
	 * that all flag changes must make sure that the mailbox signals mailbox#hasMail.
	 */
	private boolean processMail(TaskMailbox mailbox) throws Exception {

		// TODO_MA 注释： 进行此检查是一种优化，以仅在预期的热路径中读取易失性数据，只有在此之后才能获取锁定。
		// Doing this check is an optimization to only have a volatile read in the expected hot path, locks are only acquired after this point.
		if(!mailbox.createBatch()) {
			// TODO_MA 注释： 我们还可以直接返回true，因为对#isMailboxLoopRunning的所有更改都必须连接到mailbox.hasMail（）== true。
			// We can also directly return true because all changes to #isMailboxLoopRunning must be connected to mailbox.hasMail() == true.
			return true;
		}

		// TODO_MA 注释： 以非阻塞方式接收邮件并执行。
		// Take mails in a non-blockingly and execute them.
		Optional<Mail> maybeMail;
		while(isMailboxLoopRunning() && (maybeMail = mailbox.tryTakeFromBatch()).isPresent()) {

			/*************************************************
			 * 
			 *  注释： 运行 Mail
			 */
			maybeMail.get().run();
		}

		// If the default action is currently not available, we can run a blocking mailbox execution until the default
		// action becomes available again.
		while(isDefaultActionUnavailable() && isMailboxLoopRunning()) {
			maybeMail = mailbox.tryTake(MIN_PRIORITY);
			if(!maybeMail.isPresent()) {
				long start = System.currentTimeMillis();
				maybeMail = Optional.of(mailbox.take(MIN_PRIORITY));
				idleTime.markEvent(System.currentTimeMillis() - start);
			}
			maybeMail.get().run();
		}

		/*************************************************
		 * 
		 *  注释： 只要 mailBox 还在运行，就返回 true
		 */
		return isMailboxLoopRunning();
	}

	/**
	 * // TODO_MA 注释： 调用此方法表示邮箱线程应（暂时）停止调用默认操作，例如因为当前没有可用的输入。
	 * Calling this method signals that the mailbox-thread should (temporarily) stop invoking the default action,
	 * e.g. because there is currently no input available.
	 */
	private MailboxDefaultAction.Suspension suspendDefaultAction() {

		Preconditions.checkState(mailbox.isMailboxThread(), "Suspending must only be called from the mailbox thread!");

		if(suspendedDefaultAction == null) {
			suspendedDefaultAction = new DefaultActionSuspension();
			ensureControlFlowSignalCheck();
		}

		return suspendedDefaultAction;
	}

	@VisibleForTesting
	public boolean isDefaultActionUnavailable() {
		return suspendedDefaultAction != null;
	}

	@VisibleForTesting
	public boolean isMailboxLoopRunning() {
		return mailboxLoopRunning;
	}

	@VisibleForTesting
	public Meter getIdleTime() {
		return idleTime;
	}

	@VisibleForTesting
	public boolean hasMail() {
		return mailbox.hasMail();
	}

	/**
	 * Helper method to make sure that the mailbox loop will check the control flow flags in the next iteration.
	 */
	private void ensureControlFlowSignalCheck() {
		// Make sure that mailbox#hasMail is true via a dummy mail so that the flag change is noticed.
		if(!mailbox.hasMail()) {
			sendControlMail(() -> {
			}, "signal check");
		}
	}

	/**
	 * Implementation of {@link MailboxDefaultAction.Controller} that is connected to a {@link MailboxProcessor}
	 * instance.
	 */
	protected static final class MailboxController implements MailboxDefaultAction.Controller {

		private final MailboxProcessor mailboxProcessor;

		protected MailboxController(MailboxProcessor mailboxProcessor) {
			this.mailboxProcessor = mailboxProcessor;
		}

		@Override
		public void allActionsCompleted() {
			mailboxProcessor.allActionsCompleted();
		}

		@Override
		public MailboxDefaultAction.Suspension suspendDefaultAction() {
			return mailboxProcessor.suspendDefaultAction();
		}
	}

	/**
	 * Represents the suspended state of the default action and offers an idempotent method to resume execution.
	 */
	private final class DefaultActionSuspension implements MailboxDefaultAction.Suspension {

		@Override
		public void resume() {
			if(mailbox.isMailboxThread()) {
				resumeInternal();
			} else {
				sendControlMail(this::resumeInternal, "resume default action");
			}
		}

		private void resumeInternal() {
			if(suspendedDefaultAction == this) {
				suspendedDefaultAction = null;
			}
		}
	}
}
