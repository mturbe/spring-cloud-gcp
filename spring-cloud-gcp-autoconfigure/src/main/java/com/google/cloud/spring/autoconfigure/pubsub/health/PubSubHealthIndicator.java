/*
 * Copyright 2017-2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud.spring.autoconfigure.pubsub.health;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import com.google.api.gax.rpc.ApiException;
import com.google.api.gax.rpc.StatusCode;
import com.google.api.gax.rpc.StatusCode.Code;
import com.google.cloud.spring.pubsub.core.PubSubTemplate;
import com.google.cloud.spring.pubsub.support.AcknowledgeablePubsubMessage;

import org.springframework.beans.factory.BeanInitializationException;
import org.springframework.boot.actuate.health.AbstractHealthIndicator;
import org.springframework.boot.actuate.health.Health;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;
import org.springframework.util.concurrent.ListenableFuture;

/**
 * Default implementation of
 * {@link org.springframework.boot.actuate.health.HealthIndicator} for Pub/Sub. Validates
 * if connection is successful by pulling message from the pubSubTemplate using
 * {@link PubSubTemplate#pullAsync(String, Integer, Boolean)}.
 *
 * <p>If a custom subscription has been specified, this health indicator will signal up
 * if messages are successfully pulled and acknowledged <b>or</b> if a successful pull is performed
 * but no messages are returned from PubSub.</p>
 *
 * <p>If no subscription has been specified, this health indicator will pull messages from a random subscription
 * that is expected not to exist. It will signal up if it is able to connect to GCP Pub/Sub APIs,
 * i.e. the pull results in a response of {@link StatusCode.Code#NOT_FOUND} or
 * {@link StatusCode.Code#PERMISSION_DENIED}.</p>
 *
 * <p>Note that <b>messages pulled from the subscription will be acknowledged</b>. Take
 * care not to configure a subscription that has a business impact or leave the custom subscription out completely.
 *
 * @author Vinicius Carvalho
 * @author Patrik Hörlin
 *
 * @since 1.2.2
 */
public class PubSubHealthIndicator extends AbstractHealthIndicator {

	/**
	 * Template used when performing health check calls.
	 */
	private final PubSubTemplate pubSubTemplate;

	/**
	 * Indicates whether a user subscription has been configured.
	 */
	private final boolean specifiedSubscription;

	/**
	 * Subscription used when health checking.
	 */
	private final String subscription;

	/**
	 * Timeout when performing health check.
	 */
	private final long timeoutMillis;

	public PubSubHealthIndicator(PubSubTemplate pubSubTemplate, String healthCheckSubscription, long timeoutMillis) {
		super("Failed to connect to Pub/Sub APIs. Check your credentials and verify you have proper access to the service.");
		Assert.notNull(pubSubTemplate, "pubSubTemplate can't be null");
		this.pubSubTemplate = pubSubTemplate;
		this.specifiedSubscription = StringUtils.hasText(healthCheckSubscription);
		if (this.specifiedSubscription) {
			this.subscription = healthCheckSubscription;
		}
		else {
			this.subscription = UUID.randomUUID().toString();
		}
		this.timeoutMillis = timeoutMillis;
	}

	protected void validateHealthCheck() {
		try {
			pullMessage();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			validationFailed(e);
		}
		catch (ExecutionException e) {
			if (!isHealthyException(e)) {
				validationFailed(e);
			}
		}
		catch (Exception e) {
			validationFailed(e);
		}
	}

	@Override
	protected void doHealthCheck(Health.Builder builder) {
		try {
			pullMessage();
			builder.up();
		}
		catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			builder.withException(e).unknown();
		}
		catch (ExecutionException e) {
			if (isHealthyException(e)) {
				builder.up();
			}
			else {
				builder.down(e);
			}
		}
		catch (TimeoutException e) {
			builder.withException(e).unknown();
		}
		catch (Exception e) {
			builder.down(e);
		}
	}

	private void pullMessage() throws InterruptedException, ExecutionException, TimeoutException {
		ListenableFuture<List<AcknowledgeablePubsubMessage>> future = pubSubTemplate.pullAsync(this.subscription, 1, true);
		List<AcknowledgeablePubsubMessage> messages = future.get(timeoutMillis, TimeUnit.MILLISECONDS);
		messages.forEach(AcknowledgeablePubsubMessage::ack);
	}

	boolean isHealthyException(ExecutionException e) {
		return !this.specifiedSubscription && isHealthyResponseForUnspecifiedSubscription(e);
	}

	private boolean isHealthyResponseForUnspecifiedSubscription(ExecutionException e) {
		Throwable t = e.getCause();
		if (t instanceof ApiException) {
			ApiException aex = (ApiException) t;
			Code errorCode = aex.getStatusCode().getCode();
			return errorCode == StatusCode.Code.NOT_FOUND || errorCode == Code.PERMISSION_DENIED;
		}
		return false;
	}

	private void validationFailed(Exception e) {
		throw new BeanInitializationException("Validation of health indicator failed", e);
	}
}
