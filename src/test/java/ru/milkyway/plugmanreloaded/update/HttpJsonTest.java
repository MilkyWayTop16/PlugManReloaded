package ru.milkyway.plugmanreloaded.update;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class HttpJsonTest {

    private HttpServer serverA;
    private HttpServer serverB;
    private String serverAUrl;
    private String serverBUrl;

    @BeforeEach
    void setUp() throws IOException {
        HttpJson.resetConfigForTest();

        serverA = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverA.setExecutor(Executors.newCachedThreadPool());
        serverA.start();
        serverAUrl = "http://127.0.0.1:" + serverA.getAddress().getPort();

        serverB = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        serverB.setExecutor(Executors.newCachedThreadPool());
        serverB.start();
        serverBUrl = "http://127.0.0.1:" + serverB.getAddress().getPort();
    }

    @AfterEach
    void tearDown() {
        if (serverA != null) {
            serverA.stop(0);
        }
        if (serverB != null) {
            serverB.stop(0);
        }
        HttpJson.resetConfigForTest();
    }

    @Test
    @DisplayName("Verify consecutive 5xx failures trip the circuit breaker and fast-fail subsequent calls")
    void testConsecutiveFailuresTripCircuitBreaker() {
        serverA.createContext("/fail", exchange -> {
            byte[] bytes = "{\"error\":\"unavailable\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String url = serverAUrl + "/fail";
        assertEquals(0, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response r1 = HttpJson.get(url);
        assertEquals(503, r1.status());
        assertFalse(r1.transportFailure());
        assertFalse(r1.ok());
        assertEquals(1, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response r2 = HttpJson.get(url);
        assertEquals(503, r2.status());
        assertEquals(2, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response r3 = HttpJson.get(url);
        assertEquals(503, r3.status());
        assertEquals(3, HttpJson.getConsecutiveFailures(url));
        assertTrue(HttpJson.isHostCircuitBreakerOpen(url));
        assertFalse(HttpJson.isHostAvailable(url));

        HttpJson.Response r4 = HttpJson.get(url);
        assertEquals(-1, r4.status());
        assertTrue(r4.transportFailure());
        assertFalse(r4.ok());

        HttpJson.RawResponse raw = HttpJson.getRaw(url);
        assertEquals(-1, raw.status());
        assertTrue(raw.transportFailure());
    }

    @Test
    @DisplayName("Verify breaker recovers after cool-off and successful probe restores normal operation")
    void testRecoveryAndSuccessfulRequestAfterRecovery() throws Exception {
        AtomicInteger mode = new AtomicInteger(503);
        serverA.createContext("/recoverable", exchange -> {
            int currentMode = mode.get();
            if (currentMode == 503) {
                byte[] bytes = "{\"error\":\"busy\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(503, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            } else {
                byte[] bytes = "{\"result\":\"ok\"}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
            }
        });

        String url = serverAUrl + "/recoverable";
        HttpJson.setCircuitBreakerDurationMsForTest(60L);

        HttpJson.get(url);
        HttpJson.get(url);
        HttpJson.get(url);
        assertTrue(HttpJson.isHostCircuitBreakerOpen(url));

        Thread.sleep(90L);
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));
        assertTrue(HttpJson.isHostAvailable(url));

        mode.set(200);
        HttpJson.Response probeResponse = HttpJson.get(url);
        assertEquals(200, probeResponse.status());
        assertTrue(probeResponse.ok());
        assertFalse(probeResponse.transportFailure());
        assertEquals(0, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response followUp = HttpJson.get(url);
        assertEquals(200, followUp.status());
        assertTrue(followUp.ok());
    }

    @Test
    @DisplayName("Verify failed probe re-trips circuit breaker")
    void testRecoveryProbeFailureReTripsBreaker() throws Exception {
        serverA.createContext("/stay-down", exchange -> {
            byte[] bytes = "{\"error\":\"still down\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String url = serverAUrl + "/stay-down";
        HttpJson.setCircuitBreakerDurationMsForTest(60L);

        HttpJson.get(url);
        HttpJson.get(url);
        HttpJson.get(url);
        assertTrue(HttpJson.isHostCircuitBreakerOpen(url));

        Thread.sleep(90L);
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response probe = HttpJson.get(url);
        assertEquals(503, probe.status());
        assertTrue(HttpJson.isHostCircuitBreakerOpen(url));

        HttpJson.Response blocked = HttpJson.get(url);
        assertEquals(-1, blocked.status());
        assertTrue(blocked.transportFailure());
    }

    @Test
    @DisplayName("Verify thread-safe handling of concurrent requests")
    void testConcurrentRequests() throws Exception {
        AtomicInteger hits = new AtomicInteger(0);
        serverA.createContext("/parallel", exchange -> {
            hits.incrementAndGet();
            byte[] bytes = "{\"status\":\"success\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String url = serverAUrl + "/parallel";
        int threadCount = 20;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);
        List<Future<HttpJson.Response>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            futures.add(executor.submit(() -> {
                latch.countDown();
                latch.await();
                return HttpJson.get(url);
            }));
        }

        for (Future<HttpJson.Response> future : futures) {
            HttpJson.Response resp = future.get(5, TimeUnit.SECONDS);
            assertEquals(200, resp.status());
            assertTrue(resp.ok());
            assertFalse(resp.transportFailure());
        }

        executor.shutdown();
        assertEquals(threadCount, hits.get());
        assertEquals(0, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));
    }

    @Test
    @DisplayName("Verify isolation between different hosts")
    void testDifferentHostsIsolation() {
        serverA.createContext("/bad", exchange -> {
            byte[] bytes = "{\"error\":\"hostA down\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(503, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        serverB.createContext("/good", exchange -> {
            byte[] bytes = "{\"status\":\"hostB fine\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String urlA = serverAUrl + "/bad";
        String urlB = serverBUrl + "/good";

        HttpJson.get(urlA);
        HttpJson.get(urlA);
        HttpJson.get(urlA);
        assertTrue(HttpJson.isHostCircuitBreakerOpen(urlA));

        assertFalse(HttpJson.isHostCircuitBreakerOpen(urlB));
        assertTrue(HttpJson.isHostAvailable(urlB));

        HttpJson.Response respB = HttpJson.get(urlB);
        assertEquals(200, respB.status());
        assertTrue(respB.ok());
        assertFalse(respB.transportFailure());
    }

    @Test
    @DisplayName("Verify HTTP 4xx does not trip circuit breaker and does not count as transport failure")
    void testHttp4xxDoesNotTripCircuitBreaker() {
        serverA.createContext("/not-found", exchange -> {
            byte[] bytes = "{\"message\":\"not found\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(404, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        serverA.createContext("/rate-limited", exchange -> {
            exchange.getResponseHeaders().add("Retry-After", "30");
            byte[] bytes = "{\"message\":\"rate limited\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(429, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String url404 = serverAUrl + "/not-found";
        for (int i = 0; i < 5; i++) {
            HttpJson.Response resp = HttpJson.get(url404);
            assertEquals(404, resp.status());
            assertFalse(resp.ok());
            assertFalse(resp.transportFailure());
            assertFalse(resp.rateLimited());
        }
        assertEquals(0, HttpJson.getConsecutiveFailures(url404));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url404));

        String url429 = serverAUrl + "/rate-limited";
        HttpJson.Response resp429 = HttpJson.get(url429);
        assertEquals(429, resp429.status());
        assertFalse(resp429.ok());
        assertFalse(resp429.transportFailure());
        assertTrue(resp429.rateLimited());
        assertEquals(0, HttpJson.getConsecutiveFailures(url429));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url429));
    }

    @Test
    @DisplayName("Verify non-JSON response returns HTTP status with null body without tripping breaker")
    void testInvalidResponseDoesNotTripCircuitBreaker() {
        serverA.createContext("/html", exchange -> {
            byte[] bytes = "<!DOCTYPE html><html><body>Error Page</body></html>".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        String url = serverAUrl + "/html";
        HttpJson.Response resp = HttpJson.get(url);
        assertEquals(200, resp.status());
        assertNull(resp.body());
        assertFalse(resp.ok());
        assertFalse(resp.transportFailure());
        assertEquals(0, HttpJson.getConsecutiveFailures(url));
        assertFalse(HttpJson.isHostCircuitBreakerOpen(url));
    }

    @Test
    @DisplayName("Verify network transport failure and socket timeout are recognized as transportFailure")
    void testTransportFailureAndTimeout() throws Exception {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        String closedUrl = "http://127.0.0.1:" + closedPort + "/unreachable";

        HttpJson.Response transportFail = HttpJson.get(closedUrl);
        assertEquals(-1, transportFail.status());
        assertTrue(transportFail.transportFailure());
        assertFalse(transportFail.ok());
        assertEquals(1, HttpJson.getConsecutiveFailures(closedUrl));

        serverA.createContext("/slow", exchange -> {
            try {
                Thread.sleep(300L);
            } catch (InterruptedException ignored) {
            }
            byte[] bytes = "{\"status\":\"too late\"}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(bytes);
            }
        });

        HttpJson.setTimeoutsForTest(2000, 100);
        String slowUrl = serverAUrl + "/slow";
        HttpJson.Response timeoutFail = HttpJson.get(slowUrl);
        assertEquals(-1, timeoutFail.status());
        assertTrue(timeoutFail.transportFailure());
        assertEquals(1, HttpJson.getConsecutiveFailures(slowUrl));
    }
}
