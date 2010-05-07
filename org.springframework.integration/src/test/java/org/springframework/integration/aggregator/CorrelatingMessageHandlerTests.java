/*
 * Copyright 2002-2010 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.integration.aggregator;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.eq;
import static org.mockito.Matchers.isA;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.internal.stubbing.answers.DoesNothing;
import org.mockito.internal.stubbing.answers.ThrowsException;
import org.mockito.runners.MockitoJUnitRunner;
import org.springframework.integration.channel.MessageChannelTemplate;
import org.springframework.integration.core.Message;
import org.springframework.integration.core.MessageChannel;
import org.springframework.integration.message.MessageBuilder;
import org.springframework.integration.message.MessageHandlingException;
import org.springframework.integration.store.MessageGroupStore;
import org.springframework.integration.store.SimpleMessageGroup;
import org.springframework.integration.store.SimpleMessageStore;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * @author Iwein Fuld
 * @author Dave Syer
 */
@RunWith(MockitoJUnitRunner.class)
public class CorrelatingMessageHandlerTests {

	private CorrelatingMessageHandler handler;

	@Mock
	private CorrelationStrategy correlationStrategy;

	private ReleaseStrategy ReleaseStrategy = new SequenceSizeReleaseStrategy();

	@Mock
	private MessageGroupProcessor processor;

	@Mock
	private MessageChannel outputChannel;

	private MessageGroupStore store = new SimpleMessageStore();

	@Before
	public void initializeSubject() {
		handler = new CorrelatingMessageHandler(processor, store, correlationStrategy,
				ReleaseStrategy);
		handler.setOutputChannel(outputChannel);
		doAnswer(new DoesNothing()).when(processor).processAndSend(isA(SimpleMessageGroup.class),
				isA(MessageChannelTemplate.class), eq(outputChannel));
	}

	@Test
	public void bufferCompletesNormally() throws Exception {
		String correlationKey = "key";
		Message<?> message1 = testMessage(correlationKey, 1, 2);
		Message<?> message2 = testMessage(correlationKey, 2, 2);

		when(correlationStrategy.getCorrelationKey(isA(Message.class))).thenReturn(correlationKey);

		handler.handleMessage(message1);
		verifyLocks(handler, 1);

		handler.handleMessage(message2);
		verifyLocks(handler, 0); // lock is removed when group is complete

		verify(correlationStrategy).getCorrelationKey(message1);
		verify(correlationStrategy).getCorrelationKey(message2);
		verify(processor).processAndSend(isA(SimpleMessageGroup.class), isA(MessageChannelTemplate.class), eq(outputChannel));
	}

	private void verifyLocks(CorrelatingMessageHandler handler, int lockCount) {
		assertEquals(lockCount, ((Map<?, ?>) ReflectionTestUtils.getField(handler, "locks")).size());
	}

	@Test
	public void bufferCompletesWithException() throws Exception {

		doAnswer(new ThrowsException(new RuntimeException("Planned test exception"))).when(processor).processAndSend(isA(SimpleMessageGroup.class),
				isA(MessageChannelTemplate.class), eq(outputChannel));

		String correlationKey = "key";
		Message<?> message1 = testMessage(correlationKey, 1, 2);
		Message<?> message2 = testMessage(correlationKey, 2, 2);

		when(correlationStrategy.getCorrelationKey(isA(Message.class))).thenReturn(correlationKey);

		handler.handleMessage(message1);
		
		try {
			handler.handleMessage(message2);
			fail("Expected MessageHandlingException");
		} catch (MessageHandlingException e) {
			assertEquals(0, store.getMessageGroup(correlationKey).size());
		}

		verify(correlationStrategy).getCorrelationKey(message1);
		verify(correlationStrategy).getCorrelationKey(message2);
		verify(processor).processAndSend(isA(SimpleMessageGroup.class), isA(MessageChannelTemplate.class), eq(outputChannel));
	}

	/*
	 * The next test verifies that when pruning happens after the completing message arrived, but before the group was
	 * processed locking prevents forced completion and the group completes normally.
	 */

	@Test
	public void shouldNotPruneWhileCompleting() throws Exception {
		String correlationKey = "key";
		final Message<?> message1 = testMessage(correlationKey, 1, 2);
		final Message<?> message2 = testMessage(correlationKey, 2, 2);
		final List<Message<?>> storedMessages = new ArrayList<Message<?>>();

		final CountDownLatch bothMessagesHandled = new CountDownLatch(2);

		when(correlationStrategy.getCorrelationKey(isA(Message.class))).thenReturn(correlationKey);

		handler.handleMessage(message1);
		bothMessagesHandled.countDown();
		storedMessages.add(message1);
		Executors.newSingleThreadExecutor().submit(new Runnable() {
			public void run() {
				handler.handleMessage(message2);
				storedMessages.add(message2);
				bothMessagesHandled.countDown();
			}
		});

		Thread.sleep(20);
		assertEquals(0, store.expireMessageGroups(10000));

		bothMessagesHandled.await();

	}

	private Message<?> testMessage(String correlationKey, int sequenceNumber, int sequenceSize) {
		return MessageBuilder.withPayload("test" + sequenceNumber).setCorrelationId(correlationKey).setSequenceNumber(
				sequenceNumber).setSequenceSize(sequenceSize).build();
	}

}
