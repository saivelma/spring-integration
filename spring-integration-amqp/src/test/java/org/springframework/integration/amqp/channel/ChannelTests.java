/*
 * Copyright 2014-2016 the original author or authors.
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

package org.springframework.integration.amqp.channel;

import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Matchers.any;
import static org.mockito.Mockito.mock;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.TimeUnit;

import org.junit.After;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;

import org.springframework.amqp.core.AmqpTemplate;
import org.springframework.amqp.core.MessageListener;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.support.converter.MessageConversionException;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.integration.amqp.config.AmqpChannelFactoryBean;
import org.springframework.integration.amqp.rule.BrokerRunning;
import org.springframework.integration.amqp.support.AmqpHeaderMapper;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.test.support.LogAdjustingTestSupport;
import org.springframework.integration.test.util.TestUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.PollableChannel;
import org.springframework.messaging.support.GenericMessage;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.annotation.DirtiesContext.ClassMode;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;

/**
 * @author Gary Russell
 * @author Artem Bilan
 * @since 4.0
 *
 */
@ContextConfiguration
@RunWith(SpringJUnit4ClassRunner.class)
@DirtiesContext(classMode = ClassMode.AFTER_EACH_TEST_METHOD)
public class ChannelTests extends LogAdjustingTestSupport {

	@ClassRule
	public static final BrokerRunning brokerIsRunning =
		BrokerRunning.isRunningWithEmptyQueues("pollableWithEP", "withEP", "testConvertFail");

	@Rule
	public ExpectedException exception = ExpectedException.none();

	@Autowired
	private PublishSubscribeAmqpChannel channel;

	@Autowired
	private PollableAmqpChannel pollableWithEP;

	@Autowired
	private PointToPointSubscribableAmqpChannel withEP;

	@Autowired
	private PublishSubscribeAmqpChannel pubSubWithEP;

	@Autowired
	private PollableChannel out;

	@Autowired
	private CachingConnectionFactory factory;

	@Autowired
	private AmqpHeaderMapper mapperIn;

	@Autowired
	private AmqpHeaderMapper mapperOut;

	public ChannelTests() {
		super("org.springframework.integration", "org.springframework.integration.amqp", "org.springframework.amqp");
	}

	@After
	public void tearDown() {
		RabbitAdmin rabbitAdmin = new RabbitAdmin(this.factory);
		rabbitAdmin.deleteExchange("si.fanout.foo");
		rabbitAdmin.deleteExchange("si.fanout.channel");
		rabbitAdmin.deleteExchange("si.fanout.pubSubWithEP");
		brokerIsRunning.removeTestQueues();
	}

	@Test
	@DirtiesContext
	public void pubSubLostConnectionTest() throws Exception {
		final CyclicBarrier latch = new CyclicBarrier(2);
		channel.subscribe(message -> {
			try {
				latch.await(10, TimeUnit.SECONDS);
			}
			catch (Exception e) {
			}
		});
		this.channel.send(new GenericMessage<String>("foo"));
		latch.await(10, TimeUnit.SECONDS);
		latch.reset();
		BlockingQueueConsumer consumer = (BlockingQueueConsumer) TestUtils.getPropertyValue(this.channel,
				"container.consumers", Set.class).iterator().next();
		factory.destroy();
		waitForNewConsumer(this.channel, consumer);
		this.channel.send(new GenericMessage<String>("bar"));
		latch.await(10, TimeUnit.SECONDS);
		this.channel.destroy();
		this.pubSubWithEP.destroy();
		assertEquals(0, TestUtils.getPropertyValue(factory, "connectionListener.delegates", Collection.class).size());
	}

	private void waitForNewConsumer(PublishSubscribeAmqpChannel channel, BlockingQueueConsumer consumer)
			throws Exception {
		BlockingQueueConsumer newConsumer = (BlockingQueueConsumer) TestUtils.getPropertyValue(channel,
				"container.consumers", Set.class).iterator().next();
		int n = 0;
		boolean newConsumerIsConsuming = newConsumer != consumer && TestUtils.getPropertyValue(newConsumer,
				"consumerTags", Map.class).size() > 0;
		while (n++ < 100 && !newConsumerIsConsuming) {
			Thread.sleep(100);
			newConsumer = (BlockingQueueConsumer) TestUtils.getPropertyValue(channel,
					"container.consumers", Set.class).iterator().next();
			newConsumerIsConsuming = newConsumer != consumer && TestUtils.getPropertyValue(newConsumer,
					"consumerTags", Map.class).size() > 0;
		}
		assertTrue("Failed to restart consumer", n < 100);
	}

	/*
	 * Verify queue is declared if not present and not declared if it is already present.
	 */
	@Test
	public void channelDeclarationTests() {
		RabbitAdmin admin = new RabbitAdmin(this.factory);
		admin.deleteQueue("implicit");
		SimpleMessageListenerContainer container = new SimpleMessageListenerContainer(this.factory);
		AmqpTemplate amqpTemplate = mock(AmqpTemplate.class);
		PointToPointSubscribableAmqpChannel channel = new PointToPointSubscribableAmqpChannel("implicit", container,
				amqpTemplate);
		channel.setBeanFactory(mock(BeanFactory.class));
		channel.afterPropertiesSet();
		assertNotNull(admin.getQueueProperties("implicit"));
		admin.deleteQueue("implicit");

		admin.deleteQueue("explicit");
		channel.setQueueName("explicit");
		channel.afterPropertiesSet();
		assertNotNull(admin.getQueueProperties("explicit"));

		admin.deleteQueue("explicit");
		admin.declareQueue(new Queue("explicit", false)); // verify no declaration if exists with non-standard props
		channel.afterPropertiesSet();
		assertNotNull(admin.getQueueProperties("explicit"));
		admin.deleteQueue("explicit");
	}

	@Test
	public void testAmqpChannelFactoryBean() throws Exception {
		AmqpChannelFactoryBean channelFactoryBean = new AmqpChannelFactoryBean();
		channelFactoryBean.setBeanFactory(mock(BeanFactory.class));
		channelFactoryBean.setConnectionFactory(this.factory);
		channelFactoryBean.setBeanName("testChannel");
		channelFactoryBean.afterPropertiesSet();
		AbstractAmqpChannel channel = channelFactoryBean.getObject();
		assertThat(channel, instanceOf(PointToPointSubscribableAmqpChannel.class));

		channelFactoryBean = new AmqpChannelFactoryBean();
		channelFactoryBean.setBeanFactory(mock(BeanFactory.class));
		channelFactoryBean.setConnectionFactory(this.factory);
		channelFactoryBean.setBeanName("testChannel");
		channelFactoryBean.setPubSub(true);
		channelFactoryBean.afterPropertiesSet();
		channel = channelFactoryBean.getObject();
		assertThat(channel, instanceOf(PublishSubscribeAmqpChannel.class));

		RabbitAdmin rabbitAdmin = new RabbitAdmin(this.factory);
		rabbitAdmin.deleteQueue("testChannel");
		rabbitAdmin.deleteExchange("si.fanout.testChannel");
	}

	@Test
	public void extractPayloadTests() throws Exception {
		Foo foo = new Foo("bar");
		Message<?> message = MessageBuilder.withPayload(foo).setHeader("baz", "qux").build();
		this.pollableWithEP.send(message);
		Message<?> received = this.pollableWithEP.receive();
		int n = 0;
		while (received == null && n++ < 100) {
			Thread.sleep(100);
			received = this.pollableWithEP.receive();
		}
		assertNotNull(received);
		assertThat((Foo) received.getPayload(), equalTo(foo));
		assertThat((String) received.getHeaders().get("baz"), equalTo("qux"));

		this.withEP.send(message);
		received = this.out.receive(10000);
		assertNotNull(received);
		assertThat((Foo) received.getPayload(), equalTo(foo));
		assertThat((String) received.getHeaders().get("baz"), equalTo("qux"));

		this.pubSubWithEP.send(message);
		received = this.out.receive(10000);
		assertNotNull(received);
		assertThat((Foo) received.getPayload(), equalTo(foo));
		assertThat((String) received.getHeaders().get("baz"), equalTo("qux"));

		assertSame(this.mapperIn, TestUtils.getPropertyValue(this.pollableWithEP, "inboundHeaderMapper"));
		assertSame(this.mapperOut, TestUtils.getPropertyValue(this.pollableWithEP, "outboundHeaderMapper"));
	}

	@Test
	public void messageConversionTests() throws Exception {
		RabbitTemplate amqpTemplate = new RabbitTemplate(this.factory);
		MessageConverter messageConverter = mock(MessageConverter.class);
		amqpTemplate.setMessageConverter(messageConverter);
		PointToPointSubscribableAmqpChannel channel = new PointToPointSubscribableAmqpChannel("testConvertFail",
				new SimpleMessageListenerContainer(this.factory), amqpTemplate);
		channel.afterPropertiesSet();
		MessageListener listener = TestUtils.getPropertyValue(channel, "container.messageListener",
				MessageListener.class);
		willThrow(new MessageConversionException("foo", new IllegalStateException("bar")))
			.given(messageConverter).fromMessage(any(org.springframework.amqp.core.Message.class));
		this.exception.expect(MessageConversionException.class);
		this.exception.expectCause(instanceOf(IllegalStateException.class));
		listener.onMessage(mock(org.springframework.amqp.core.Message.class));
	}

	public static class Foo {

		private String bar;

		public Foo() {
		}

		public Foo(String bar) {
			this.bar = bar;
		}

		public String getBar() {
			return this.bar;
		}

		public void setBar(String bar) {
			this.bar = bar;
		}

		@Override
		public int hashCode() {
			final int prime = 31;
			int result = 1;
			result = prime * result + ((this.bar == null) ? 0 : this.bar.hashCode());
			return result;
		}

		@Override
		public boolean equals(Object obj) {
			if (this == obj) {
				return true;
			}
			if (obj == null) {
				return false;
			}
			if (getClass() != obj.getClass()) {
				return false;
			}
			Foo other = (Foo) obj;
			if (this.bar == null) {
				if (other.bar != null) {
					return false;
				}
			}
			else if (!this.bar.equals(other.bar)) {
				return false;
			}
			return true;
		}

	}

}
