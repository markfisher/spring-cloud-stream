/*
 * Copyright 2013-2016 the original author or authors.
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

package org.springframework.cloud.stream.binder;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.mockito.Matchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mockito;

import org.springframework.beans.factory.BeanFactory;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.cloud.stream.binder.local.LocalMessageChannelBinder;
import org.springframework.cloud.stream.binding.BinderAwareChannelResolver;
import org.springframework.context.support.StaticApplicationContext;
import org.springframework.integration.channel.DirectChannel;
import org.springframework.integration.scheduling.PollerMetadata;
import org.springframework.integration.support.DefaultMessageBuilderFactory;
import org.springframework.integration.support.MessageBuilder;
import org.springframework.integration.support.utils.IntegrationUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageChannel;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.PeriodicTrigger;

/**
 * @author Mark Fisher
 * @author Gary Russell
 */
public class BinderAwareChannelResolverTests {

	private final StaticApplicationContext context = new StaticApplicationContext();

	private volatile BinderAwareChannelResolver resolver;

	private volatile LocalMessageChannelBinder binder;

	@Before
	public void setupContext() throws Exception {
		this.binder = new LocalMessageChannelBinder();
		this.binder.setApplicationContext(context);
		this.binder.afterPropertiesSet();
		this.resolver = new BinderAwareChannelResolver(new BinderFactory<MessageChannel>() {
			@Override
			public Binder<MessageChannel> getBinder(String configurationName) {
				return binder;
			}
		}, null);
		this.resolver.setBeanFactory(context);
		context.getBeanFactory().registerSingleton("channelResolver",
				this.resolver);
		context.registerSingleton("other", DirectChannel.class);
		context.registerSingleton("taskScheduler", ThreadPoolTaskScheduler.class);
		context.registerSingleton(IntegrationUtils.INTEGRATION_MESSAGE_BUILDER_FACTORY_BEAN_NAME,
				DefaultMessageBuilderFactory.class);
		context.refresh();

		PollerMetadata poller = new PollerMetadata();
		poller.setTrigger(new PeriodicTrigger(1000));
		binder.setPoller(poller);
	}

	@Test
	public void resolveChannel() {
		MessageChannel registered = resolver.resolveDestination("foo");
		DirectChannel testChannel = new DirectChannel();
		final CountDownLatch latch = new CountDownLatch(1);
		final List<Message<?>> received = new ArrayList<Message<?>>();
		testChannel.subscribe(new MessageHandler() {

			@Override
			public void handleMessage(Message<?> message) throws MessagingException {
				received.add(message);
				latch.countDown();
			}
		});
		binder.bindConsumer("foo", null, testChannel, null);
		assertEquals(0, received.size());
		registered.send(MessageBuilder.withPayload("hello").build());
		try {
			assertTrue("latch timed out", latch.await(1, TimeUnit.SECONDS));
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			fail("interrupted while awaiting latch");
		}
		assertEquals(1, received.size());
		assertEquals("hello", received.get(0).getPayload());
		context.close();
	}

	@Test
	public void resolveNonRegisteredChannel() {
		MessageChannel other = resolver.resolveDestination("other");
		assertSame(context.getBean("other"), other);
	}

	@Test
	@SuppressWarnings("rawtypes")
	public void propertyPassthrough() {
		Properties properties = new Properties();
		Binder binderFactory = mock(Binder.class);
		doReturn(new DirectChannel()).when(binderFactory).bindDynamicProducer("foo", properties);
		BinderFactory mockBinderFactory = Mockito.mock(BinderFactory.class);
		Mockito.when(mockBinderFactory.getBinder(anyString())).thenReturn(binderFactory);
		@SuppressWarnings("unchecked")
		BinderAwareChannelResolver resolver =
				new BinderAwareChannelResolver(mockBinderFactory, properties);
		BeanFactory beanFactory = new DefaultListableBeanFactory();
		resolver.setBeanFactory(beanFactory);
		resolver.resolveDestination("foo");
		verify(binderFactory).bindDynamicProducer("foo", properties);
	}

}
