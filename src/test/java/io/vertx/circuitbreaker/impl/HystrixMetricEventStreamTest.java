package io.vertx.circuitbreaker.impl;

import io.vertx.circuitbreaker.CircuitBreaker;
import io.vertx.circuitbreaker.CircuitBreakerOptions;
import io.vertx.circuitbreaker.HystrixMetricHandler;
import io.vertx.core.Future;
import io.vertx.core.Handler;
import io.vertx.core.Promise;
import io.vertx.core.Vertx;
import io.vertx.core.http.HttpClient;
import io.vertx.core.json.JsonObject;
import io.vertx.core.parsetools.RecordParser;
import io.vertx.ext.unit.TestContext;
import io.vertx.ext.unit.junit.Repeat;
import io.vertx.ext.unit.junit.RepeatRule;
import io.vertx.ext.unit.junit.VertxUnitRunner;
import io.vertx.ext.web.Router;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jayway.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.core.Is.is;

/**
 * @author <a href="http://escoffier.me">Clement Escoffier</a>
 */
@RunWith(VertxUnitRunner.class)
public class HystrixMetricEventStreamTest {

  @Rule
  public RepeatRule rule = new RepeatRule();


  private CircuitBreaker breakerA;
  private CircuitBreaker breakerB;
  private CircuitBreaker breakerC;


  private Vertx vertx;

  @Before
  public void setUp(TestContext tc) {
    vertx = Vertx.vertx();
    vertx.exceptionHandler(tc.exceptionHandler());
  }

  @After
  public void tearDown() {
    if (breakerA != null) {
      breakerA.close();
    }
    if (breakerB != null) {
      breakerB.close();
    }
    if (breakerC != null) {
      breakerC.close();
    }

    AtomicBoolean completed = new AtomicBoolean();
    vertx.close(ar -> completed.set(ar.succeeded()));
    await().untilAtomic(completed, is(true));
  }


  @Test
  @Repeat(10)
  public void test() {
    breakerA = CircuitBreaker.create("A", vertx, new CircuitBreakerOptions().setTimeout(1000));
    breakerB = CircuitBreaker.create("B", vertx, new CircuitBreakerOptions().setTimeout(1000));
    breakerC = CircuitBreaker.create("C", vertx, new CircuitBreakerOptions().setTimeout(1000));

    Router router = Router.router(vertx);
    router.get("/metrics").handler(HystrixMetricHandler.create(vertx));

    AtomicBoolean ready = new AtomicBoolean();
    vertx.createHttpServer()
      .requestHandler(router::accept)
      .listen(8080, ar -> ready.set(ar.succeeded()));

    await().untilAtomic(ready, is(true));

    List<JsonObject> responses = new CopyOnWriteArrayList<>();
    HttpClient client = vertx.createHttpClient();

    RecordParser parser = RecordParser.newDelimited("\n\n", buffer -> {
      String record = buffer.toString();
      String[] lines = record.split("\n");
      for (String line : lines) {
        String l = line.trim();
        if (l.startsWith("data:")) {
          String json = l.substring("data:".length());
          JsonObject object = new JsonObject(json);
          responses.add(object);
        }
      }
    });

    client.get(8080, "localhost", "/metrics")
      .handler(response
        -> response.handler(parser))
      .end();

    for (int i = 0; i < 1000; i++) {
      breakerA.execute(choose());
      breakerB.execute(choose());
      breakerC.execute(choose());
    }

    await().atMost(1, TimeUnit.MINUTES).until(() -> responses.size() > 100);

    // Check that we got metrics for A, B and C
    JsonObject a = null;
    JsonObject b = null;
    JsonObject c = null;
    for (JsonObject json : responses) {
      if (json.getString("name").equals("A")) {
        a = json;
      } else if (json.getString("name").equals("B")) {
        b = json;
      } else if (json.getString("name").equals("C")) {
        c = json;
      }
    }

    assertThat((a == null && b == null && c == null));

  }


  private Random random = new Random();

  private Handler<Promise<Void>> choose() {
    int choice = random.nextInt(5);
    switch (choice) {
      case 0:
        return commandThatWorks();
      case 1:
        return commandThatFails();
      case 2:
        return commandThatCrashes();
      case 3:
        return commandThatTimeout(1000);
      case 4:
        return commandThatTimeoutAndFail(1000);
    }
    return commandThatWorks();
  }


  private Handler<Promise<Void>> commandThatWorks() {
    return (future -> vertx.setTimer(5, l -> future.complete(null)));
  }

  private Handler<Promise<Void>> commandThatFails() {
    return (future -> vertx.setTimer(5, l -> future.fail("expected failure")));
  }

  private Handler<Promise<Void>> commandThatCrashes() {
    return (future -> {
      throw new RuntimeException("Expected error");
    });
  }

  private Handler<Promise<Void>> commandThatTimeout(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.complete(null)));
  }

  private Handler<Promise<Void>> commandThatTimeoutAndFail(int timeout) {
    return (future -> vertx.setTimer(timeout + 500, l -> future.fail("late failure")));
  }


}
