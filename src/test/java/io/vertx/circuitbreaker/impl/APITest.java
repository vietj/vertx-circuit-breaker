/*
 * Copyright (c) 2011-2016 The original author or authors
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *      The Eclipse Public License is available at
 *      http://www.eclipse.org/legal/epl-v10.html
 *
 *      The Apache License v2.0 is available at
 *      http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 */

package io.vertx.circuitbreaker.impl;

import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.CircuitBreakerState;
import io.vertx.core.Future;
import io.vertx.core.Vertx;
import io.vertx.circuitbreaker.CircuitBreaker;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
public class APITest {

  private CircuitBreaker breaker;
  private Vertx vertx;

  @Before
  public void setUp() {
    vertx = Vertx.vertx();
  }

  @After
  public void tearDown() {
    if (breaker != null) {
      breaker.close();
    }
    AtomicBoolean completed = new AtomicBoolean();
    vertx.close(ar -> completed.set(ar.succeeded()));
    await().untilAtomic(completed, is(true));
  }

  /**
   * Reproducer of https://github.com/vert-x3/vertx-circuit-breaker/issues/9
   */
  @Test
  public void testWhenOptionsAreNull() {
    CircuitBreaker cb = CircuitBreaker.create("name", vertx, null);
    assertThat(cb).isNotNull();
    assertThat(cb.name()).isEqualTo("name");
    assertThat(cb.state()).isEqualTo(CircuitBreakerState.CLOSED);
  }


  @Test
  public void testWithOperationWithHandler() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions());

    AtomicInteger result = new AtomicInteger();

    breaker.<Integer>executeWithFallback(fut -> {
      MyAsyncOperations.operation(1, 1, fut);
    }, v -> 0)
        .setHandler(ar -> result.set(ar.result()));

    await().untilAtomic(result, is(2));
  }

  @Test
  public void testWithOperationWithCompletionHandler() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions());

    AtomicInteger result = new AtomicInteger();

    breaker.executeCommandWithFallback(fut -> {
      MyAsyncOperations.operation(1, 1, fut);
    }, v -> 0, ar -> result.set(ar.result()));

    await().untilAtomic(result, is(2));
  }

  @Test
  public void testWithFailingOperationWithHandler() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions()
        .setFallbackOnFailure(true));

    AtomicInteger result = new AtomicInteger();

    breaker.<Integer>executeWithFallback(fut -> {
      MyAsyncOperations.fail(fut);
    }, v -> -1)
        .setHandler(ar -> result.set(ar.result()));

    await().untilAtomic(result, is(-1));
  }

  @Test
  public void testWithFailingOperationWithCompletionHandler() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions()
      .setFallbackOnFailure(true));

    AtomicInteger result = new AtomicInteger();

    breaker.<Integer>executeCommandWithFallback(fut -> {
      MyAsyncOperations.fail(fut);
    }, v -> -1, ar -> result.set(ar.result()));

    await().untilAtomic(result, is(-1));
  }


  @Test
  public void testWithOperationWithFuture() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions()
        .setFallbackOnFailure(true));

    AtomicInteger result = new AtomicInteger();
    Future<Integer> operationResultFuture = Future.future();
    operationResultFuture.setHandler(ar -> {
      result.set(ar.result());
    });

    breaker.executeAndReport(operationResultFuture, future -> MyAsyncOperations.operation(future, 1, 1));

    await().untilAtomic(result, is(2));
  }

  @Test
  public void testWithFailingOperationWithFuture() {
    breaker = CircuitBreaker.create("test", vertx, new CircuitBreakerOptions()
        .setFallbackOnFailure(true));

    AtomicInteger result = new AtomicInteger();

    Future<Integer> operationResultFuture = Future.future();
    operationResultFuture.setHandler(ar -> result.set(ar.result()));

    breaker.executeAndReportWithFallback(operationResultFuture, MyAsyncOperations::fail, t -> -1);

    await().untilAtomic(result, is(-1));
  }


}
