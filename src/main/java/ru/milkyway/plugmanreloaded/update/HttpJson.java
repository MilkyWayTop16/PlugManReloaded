package ru.milkyway.plugmanreloaded.update;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import org.jetbrains.annotations.Nullable;
import ru.milkyway.plugmanreloaded.utils.Log;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public final class HttpJson {

    public static String encodePath(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    public static final int STATUS_RATE_LIMITED = 429;
    private static final Gson GSON = new Gson();

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 10000;
    private static final int MAX_BODY_BYTES = 8 * 1024 * 1024;
    private static final long DEFAULT_CIRCUIT_BREAKER_DURATION_MS = 45_000L;
    private static final int DEFAULT_MAX_CONSECUTIVE_FAILS = 3;

    private static volatile int connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
    private static volatile int readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
    private static volatile long circuitBreakerDurationMs = DEFAULT_CIRCUIT_BREAKER_DURATION_MS;
    private static volatile int maxConsecutiveFails = DEFAULT_MAX_CONSECUTIVE_FAILS;

    private static final Map<String, HostCircuitBreaker> HOST_BREAKERS = new ConcurrentHashMap<>();

    private static String userAgent = "PlugManReloaded";

    static {
        System.setProperty("http.maxConnections", "64");
    }

    private HttpJson() {}

    private static final class HostCircuitBreaker {
        private final AtomicInteger consecutiveFailures = new AtomicInteger(0);
        private final AtomicLong deadUntil = new AtomicLong(0L);
        private final AtomicBoolean halfOpenProbeInFlight = new AtomicBoolean(false);

        boolean isDead() {
            long until = deadUntil.get();
            return until > 0L && System.currentTimeMillis() < until;
        }

        boolean tryAcquire() {
            long until = deadUntil.get();
            if (until == 0L) {
                return true;
            }
            long now = System.currentTimeMillis();
            if (now < until) {
                return false;
            }
            return halfOpenProbeInFlight.compareAndSet(false, true);
        }

        boolean recordFailure() {
            if (halfOpenProbeInFlight.getAndSet(false)) {
                deadUntil.set(System.currentTimeMillis() + circuitBreakerDurationMs);
                return true;
            }
            if (consecutiveFailures.incrementAndGet() >= maxConsecutiveFails) {
                deadUntil.set(System.currentTimeMillis() + circuitBreakerDurationMs);
                return true;
            }
            return false;
        }

        void recordSuccess() {
            consecutiveFailures.set(0);
            deadUntil.set(0L);
            halfOpenProbeInFlight.set(false);
        }

        int getFailures() {
            return consecutiveFailures.get();
        }
    }

    public static boolean isHostAvailable(String url) {
        String host = extractHost(url);
        if (host == null) return true;
        HostCircuitBreaker breaker = HOST_BREAKERS.get(host);
        return breaker == null || !breaker.isDead();
    }

    private static boolean checkAndAcquire(String url) {
        String host = extractHost(url);
        if (host == null) return true;
        HostCircuitBreaker breaker = HOST_BREAKERS.get(host);
        return breaker == null || breaker.tryAcquire();
    }

    private static void recordFailure(String url) {
        String host = extractHost(url);
        if (host == null) return;
        HostCircuitBreaker breaker = HOST_BREAKERS.computeIfAbsent(host, k -> new HostCircuitBreaker());
        if (breaker.recordFailure()) {
            Log.debugPlain("httpjson.host-disabled", "host", host);
        }
    }

    private static void recordSuccess(String url) {
        String host = extractHost(url);
        if (host == null) return;
        HostCircuitBreaker breaker = HOST_BREAKERS.get(host);
        if (breaker != null) {
            breaker.recordSuccess();
        }
    }

    private static @Nullable String extractHost(String url) {
        try {
            URL parsed = new URL(url);
            String host = parsed.getHost();
            if (host == null || host.isBlank()) {
                return null;
            }
            int port = parsed.getPort();
            if (port != -1 && port != parsed.getDefaultPort()) {
                return (host + ":" + port).toLowerCase(Locale.ROOT);
            }
            return host.toLowerCase(Locale.ROOT);
        } catch (Exception e) {
            return null;
        }
    }

    public static void setUserAgent(String value) {
        if (value != null && !value.isBlank()) {
            userAgent = value;
        }
    }

    public record Response(int status, JsonElement body, boolean quotaExhausted) {
        public Response(int status, JsonElement body) {
            this(status, body, false);
        }

        public boolean ok() {
            return status >= 200 && status < 300 && body != null;
        }

        public boolean rateLimited() {
            return status == STATUS_RATE_LIMITED || quotaExhausted;
        }

        public boolean transportFailure() {
            return status == -1;
        }
    }

    public record RawResponse(int status, String body) {
        public boolean ok() {
            return status >= 200 && status < 300 && body != null && !body.isBlank();
        }

        public boolean transportFailure() {
            return status == -1;
        }
    }

    public static RawResponse getRaw(String url) {
        if (!checkAndAcquire(url)) {
            return new RawResponse(-1, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8");
            connection.setRequestProperty("Accept-Language", "ru-RU,ru;q=0.9,en-US;q=0.8,en;q=0.7");

            int status = connection.getResponseCode();
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String result = stream != null ? readLimited(stream) : null;

            if (status >= 200 && status < 500) {
                recordSuccess(url);
            } else {
                recordFailure(url);
            }

            return new RawResponse(status, result);
        } catch (Throwable t) {
            recordFailure(url);
            Log.debugPlain("httpjson.raw-request-failed", "url", url, "error", t.getMessage());
            return new RawResponse(-1, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    public static Response get(String url) {
        return request("GET", url, null, null);
    }

    public static Response postJson(String url, String jsonBody) {
        return request("POST", url, jsonBody, null);
    }

    public static Response get(String url, String authorization) {
        return request("GET", url, null, authorization);
    }

    private static Response request(String method, String url, String body, String authorization) {
        if (!checkAndAcquire(url)) {
            return new Response(-1, null);
        }
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod(method);
            connection.setConnectTimeout(connectTimeoutMs);
            connection.setReadTimeout(readTimeoutMs);
            connection.setRequestProperty("User-Agent", userAgent);
            connection.setRequestProperty("Accept", "application/json");
            if (authorization != null && !authorization.isBlank()) {
                connection.setRequestProperty("Authorization", authorization);
            }

            if (body != null) {
                connection.setDoOutput(true);
                connection.setRequestProperty("Content-Type", "application/json");
                byte[] payload = body.getBytes(StandardCharsets.UTF_8);
                try (OutputStream out = connection.getOutputStream()) {
                    out.write(payload);
                }
            }

            int status = connection.getResponseCode();
            boolean quotaExhausted = isQuotaExhausted(connection, status);
            InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = stream != null ? readLimited(stream) : null;

            if (status >= 200 && status < 500) {
                recordSuccess(url);
            } else {
                recordFailure(url);
            }

            if (text == null || text.isBlank()) {
                return new Response(status, null, quotaExhausted);
            }

            JsonElement json = null;
            try {
                json = GSON.fromJson(text, JsonElement.class);
            } catch (Exception parseException) {
                Log.debugPlain("httpjson.request-failed", "method", "PARSE", "url", url, "error", parseException.getMessage());
            }

            return new Response(status, json, quotaExhausted);

        } catch (Throwable t) {
            recordFailure(url);
            Log.debugPlain("httpjson.request-failed", "method", method, "url", url, "error", t.getMessage());
            return new Response(-1, null);
        } finally {
            if (connection != null) {
                connection.disconnect();
            }
        }
    }

    private static boolean isQuotaExhausted(HttpURLConnection connection, int status) {
        if (status != 403 && status != STATUS_RATE_LIMITED) {
            return false;
        }
        if (status == STATUS_RATE_LIMITED) {
            return true;
        }
        String remaining = connection.getHeaderField("x-ratelimit-remaining");
        if (remaining != null) {
            try {
                return Integer.parseInt(remaining.trim()) <= 0;
            } catch (NumberFormatException malformed) {
                Log.debugPlain("httpjson.ratelimit-header-unreadable", "value", remaining);
            }
        }
        return connection.getHeaderField("retry-after") != null;
    }

    private static String readLimited(InputStream stream) throws Exception {
        StringBuilder builder = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            char[] buffer = new char[8192];
            int read;
            while ((read = reader.read(buffer)) != -1) {
                if (builder.length() + read > MAX_BODY_BYTES) {
                    Log.warn("httpjson.response-too-large");
                    return "";
                }
                builder.append(buffer, 0, read);
            }
        }
        return builder.toString();
    }

    static void resetCircuitBreakers() {
        HOST_BREAKERS.clear();
    }

    static void setCircuitBreakerDurationMsForTest(long ms) {
        circuitBreakerDurationMs = ms;
    }

    static void setMaxConsecutiveFailsForTest(int count) {
        maxConsecutiveFails = count;
    }

    static void setTimeoutsForTest(int connectTimeout, int readTimeout) {
        connectTimeoutMs = connectTimeout;
        readTimeoutMs = readTimeout;
    }

    static void resetConfigForTest() {
        circuitBreakerDurationMs = DEFAULT_CIRCUIT_BREAKER_DURATION_MS;
        maxConsecutiveFails = DEFAULT_MAX_CONSECUTIVE_FAILS;
        connectTimeoutMs = DEFAULT_CONNECT_TIMEOUT_MS;
        readTimeoutMs = DEFAULT_READ_TIMEOUT_MS;
        HOST_BREAKERS.clear();
    }

    static int getConsecutiveFailures(String url) {
        String host = extractHost(url);
        if (host == null) return 0;
        HostCircuitBreaker breaker = HOST_BREAKERS.get(host);
        return breaker != null ? breaker.getFailures() : 0;
    }

    static boolean isHostCircuitBreakerOpen(String url) {
        String host = extractHost(url);
        if (host == null) return false;
        HostCircuitBreaker breaker = HOST_BREAKERS.get(host);
        return breaker != null && breaker.isDead();
    }
}
