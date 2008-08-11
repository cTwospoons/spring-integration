/*
 * Copyright 2002-2008 the original author or authors.
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

package org.springframework.integration.endpoint;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.integration.channel.ChannelRegistry;
import org.springframework.integration.channel.ChannelRegistryAware;
import org.springframework.integration.channel.MessageChannel;
import org.springframework.integration.handler.MessageHandler;
import org.springframework.integration.message.Message;
import org.springframework.integration.message.MessageExchangeTemplate;
import org.springframework.integration.message.MessageRejectedException;
import org.springframework.integration.message.MessageSource;
import org.springframework.integration.message.MessageTarget;
import org.springframework.integration.message.selector.MessageSelector;
import org.springframework.integration.scheduling.Schedule;
import org.springframework.util.Assert;

/**
 * Base class for {@link MessageEndpoint} implementations.
 * 
 * @author Mark Fisher
 */
public abstract class AbstractEndpoint implements MessageEndpoint, ChannelRegistryAware, InitializingBean, BeanNameAware {

	protected final Log logger = LogFactory.getLog(this.getClass());

	private volatile String name;

	private volatile String inputChannelName;

	private volatile String outputChannelName;

	private volatile MessageSource<?> source;

	private volatile MessageTarget target;

	private volatile Schedule schedule;

	private volatile MessageExchangeTemplate messageExchangeTemplate;

	private volatile MessageSelector selector;

	private final List<EndpointInterceptor> interceptors = new ArrayList<EndpointInterceptor>();

	private volatile ChannelRegistry channelRegistry;


	public String getName() {
		return this.name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public void setBeanName(String beanName) {
		this.setName(beanName);
	}

	public void setSchedule(Schedule schedule) {
		this.schedule = schedule;
	}

	public Schedule getSchedule() {
		return this.schedule;
	}

	public void setMessageExchangeTemplate(MessageExchangeTemplate messageExchangeTemplate) {
		Assert.notNull(messageExchangeTemplate, "messageExchangeTemplate must not be null");
		this.messageExchangeTemplate = messageExchangeTemplate;
	}

	public MessageExchangeTemplate getMessageExchangeTemplate() {
		if (this.messageExchangeTemplate == null) {
			this.messageExchangeTemplate = new MessageExchangeTemplate();
			this.messageExchangeTemplate.afterPropertiesSet();
		}
		return this.messageExchangeTemplate;
	}

	public void setInputChannelName(String inputChannelName) {
		this.inputChannelName = inputChannelName;
	}

	public String getInputChannelName() {
		return this.inputChannelName;
	}

	public void setSource(MessageSource<?> source) {
		if (source instanceof MessageChannel) {
			this.inputChannelName = ((MessageChannel) source).getName();
		}
		this.source = source;
	}

	public MessageSource<?> getSource() {
		if (this.source == null && this.inputChannelName != null && this.channelRegistry != null) {
			MessageChannel inputChannel = this.channelRegistry.lookupChannel(this.inputChannelName);
			if (inputChannel != null) {
				this.source = inputChannel;
			}
		}
		return this.source;
	}

	/**
	 * Set the name of the channel to which this endpoint should send reply
	 * messages.
	 */
	public void setOutputChannelName(String outputChannelName) {
		this.outputChannelName = outputChannelName;
	}

	public String getOutputChannelName() {
		return this.outputChannelName;
	}

	public void setTarget(MessageTarget target) {
		if (target instanceof MessageChannel) {
			this.outputChannelName = ((MessageChannel) target).getName();
		}
		this.target = target;
	}

	public void setSendTimeout(long sendTimeout) {
		this.getMessageExchangeTemplate().setSendTimeout(sendTimeout);
	}

	public MessageTarget getTarget() {
		if (this.target == null && this.outputChannelName != null && this.channelRegistry != null) {
			MessageChannel outputChannel = this.channelRegistry.lookupChannel(this.outputChannelName);
			if (outputChannel != null) {
				this.target = outputChannel;
			}
		}
		return this.target;
	}

	/**
	 * Set the channel registry to use for looking up channels by name.
	 */
	public void setChannelRegistry(ChannelRegistry channelRegistry) {
		this.channelRegistry = channelRegistry;
	}

	protected ChannelRegistry getChannelRegistry() {
		return this.channelRegistry;
	}

	public void setSelector(MessageSelector selector) {
		this.selector = selector;
	}

	public void addInterceptor(EndpointInterceptor interceptor) {
		this.interceptors.add(interceptor);
	}

	public void setInterceptors(List<EndpointInterceptor> interceptors) {
		this.interceptors.clear();
		for (EndpointInterceptor interceptor : interceptors) {
			this.addInterceptor(interceptor);
		}
	}

	public String toString() {
		return (this.name != null) ? this.name : super.toString();
	}

	protected void initialize() {
	}

	public void afterPropertiesSet() {
		if (this.target == null) {
			this.target = this.getTarget();
		}
		if (this.target != null && this.target instanceof ChannelRegistryAware
				&& this.channelRegistry != null) {
			((ChannelRegistryAware) this.target).setChannelRegistry(this.channelRegistry);
		}
		this.initialize();
	}

	public final boolean send(Message<?> message) {
		if (message == null || message.getPayload() == null) {
			throw new IllegalArgumentException("Message and its payload must not be null.");
		}
		if (logger.isDebugEnabled()) {
			logger.debug("endpoint '" + this + "' handling message: " + message);
		}
		this.handleMessage(message, 0);
		return true;
	}

	private Message<?> handleMessage(Message<?> message, final int index) {
		if (index == 0) {
			for (EndpointInterceptor interceptor : interceptors) {
				message = interceptor.preHandle(message);
				if (message == null) {
					return null;
				}
			}
		}
		if (index == interceptors.size()) {
			if (!this.supports(message)) {
				throw new MessageRejectedException(message, "unsupported message");
			}
			Message<?> reply = this.handleMessage(message);
			for (int i = index - 1; i >= 0; i--) {
				EndpointInterceptor interceptor = this.interceptors.get(i);
				reply = interceptor.postHandle(message, reply);
			}
			if (reply != null) {
				this.getMessageExchangeTemplate().send(message, this.target);
			}
			return null;
		}
		EndpointInterceptor nextInterceptor = interceptors.get(index);
		return nextInterceptor.aroundHandle(message, new MessageHandler() {
			public Message<?> handle(Message<?> message) {
				return AbstractEndpoint.this.handleMessage(message, index + 1);
			}
		});
	}

	protected boolean supports(Message<?> message) {
		if (this.selector != null && !this.selector.accept(message)) {
			if (logger.isDebugEnabled()) {
				logger.debug("selector for endpoint '" + this + "' rejected message: " + message);
			}
			return false;
		}
		return true;
	}

	protected Message<?> handleMessage(Message<?> message) {
		return message;
	}

}
