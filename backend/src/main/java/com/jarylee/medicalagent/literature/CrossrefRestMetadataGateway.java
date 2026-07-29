package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "medical.crossref.mode", havingValue = "live")
public class CrossrefRestMetadataGateway implements CrossrefMetadataGateway {
    public static final String TOOL_VERSION = "crossref-rest/v1";
    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(429, 500, 502, 503, 504);
    private static final String DOI_PATTERN = "10\\.\\d{4,9}/[-._;()/:A-Za-z0-9]+";

    private final ObjectMapper json;
    private final RestClient client;
    private final String mailto;
    private final int maxAttempts;
    private final RequestThrottle throttle;
    private final Cache<String, CrossrefMetadataModels.GatewayResult> cache;

    public CrossrefRestMetadataGateway(
            ObjectMapper json,
            RestClient.Builder builder,
            @Value("${medical.crossref.base-url:https://api.crossref.org}") String baseUrl,
            @Value("${medical.crossref.user-agent:medical-research-agent/1.0}")
            String userAgent,
            @Value("${medical.crossref.mailto:}") String mailto,
            @Value("${medical.crossref.connect-timeout:5s}") Duration connectTimeout,
            @Value("${medical.crossref.read-timeout:30s}") Duration readTimeout,
            @Value("${medical.crossref.max-attempts:3}") int maxAttempts,
            @Value("${medical.crossref.cache-ttl:24h}") Duration cacheTtl) {
        this(json, builder, baseUrl, userAgent, mailto, connectTimeout, readTimeout,
                maxAttempts, cacheTtl,
                mailto == null || mailto.isBlank()
                        ? Duration.ofMillis(250) : Duration.ofMillis(110));
    }

    CrossrefRestMetadataGateway(
            ObjectMapper json, RestClient.Builder builder, String baseUrl,
            String userAgent, String mailto, Duration connectTimeout,
            Duration readTimeout, int maxAttempts, Duration cacheTtl,
            Duration minimumInterval) {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("Crossref最大重试次数必须在1到5之间");
        }
        if (cacheTtl.isNegative() || cacheTtl.isZero()
                || cacheTtl.compareTo(Duration.ofDays(7)) > 0) {
            throw new IllegalArgumentException("Crossref缓存时间必须在0到7天之间");
        }
        this.json = json;
        this.mailto = validateOptionalEmail(mailto);
        this.maxAttempts = maxAttempts;
        this.throttle = new RequestThrottle(minimumInterval);
        this.cache = Caffeine.newBuilder()
                .maximumSize(1000)
                .expireAfterWrite(cacheTtl)
                .build();
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder.baseUrl(stripTrailingSlash(baseUrl))
                .defaultHeader("User-Agent", validateUserAgent(userAgent))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public CrossrefMetadataModels.GatewayResult lookup(String doi) {
        String normalized = normalizeDoi(doi);
        var cached = cache.getIfPresent(normalized);
        if (cached != null) {
            return new CrossrefMetadataModels.GatewayResult(
                    cached.found(), cached.work(), cached.rawResponse(),
                    cached.rawContentType(), cached.toolVersion(), 0, true);
        }
        HttpResult response = get(normalized);
        CrossrefMetadataModels.GatewayResult result = response.notFound()
                ? new CrossrefMetadataModels.GatewayResult(
                        false, null, response.body().getBytes(StandardCharsets.UTF_8),
                        response.contentType(), TOOL_VERSION, response.requestCount(), false)
                : parse(normalized, response);
        cache.put(normalized, result);
        return result;
    }

    private HttpResult get(String doi) {
        RestClientResponseException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle.acquire();
            try {
                var response = client.get()
                        .uri(uri -> {
                            // A DOI contains a slash. Appending it as path text preserves
                            // Crossref's /works/{prefix}/{suffix} route, whereas a URI
                            // template variable would encode the slash as %2F.
                            var path = uri.path("/works/").path(doi);
                            if (!mailto.isBlank()) path.queryParam("mailto", mailto);
                            return path.build();
                        })
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(String.class);
                String body = response.getBody();
                if (body == null || body.isBlank()) {
                    throw new CrossrefMetadataException(
                            "CROSSREF_RESPONSE_INVALID", "Crossref返回空响应");
                }
                return new HttpResult(body, contentType(response.getHeaders().getContentType()),
                        attempt, false);
            } catch (RestClientResponseException exception) {
                last = exception;
                int status = exception.getStatusCode().value();
                if (status == 404) {
                    String body = exception.getResponseBodyAsString(StandardCharsets.UTF_8);
                    return new HttpResult(
                            body == null ? "" : body,
                            contentType(exception.getResponseHeaders() == null
                                    ? null : exception.getResponseHeaders().getContentType()),
                            attempt, true);
                }
                if (!RETRYABLE_STATUS.contains(status) || attempt == maxAttempts) {
                    throw new CrossrefMetadataException(
                            "CROSSREF_HTTP_ERROR", "Crossref调用失败，HTTP " + status);
                }
                pause(backoffMillis(exception, attempt));
            } catch (CrossrefMetadataException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (attempt == maxAttempts) {
                    throw new CrossrefMetadataException(
                            "CROSSREF_UNAVAILABLE", "Crossref服务不可用", exception);
                }
                pause(Math.min(5_000, 250L << (attempt - 1)));
            }
        }
        throw new CrossrefMetadataException(
                "CROSSREF_UNAVAILABLE", "Crossref服务不可用", last);
    }

    private CrossrefMetadataModels.GatewayResult parse(String requestedDoi, HttpResult response) {
        try {
            JsonNode root = json.readTree(response.body());
            if (!"ok".equalsIgnoreCase(root.path("status").asText())
                    || !root.path("message").isObject()) {
                throw invalid("Crossref响应缺少work记录");
            }
            JsonNode work = root.path("message");
            String returnedDoi = normalizeDoi(required(work.path("DOI"), "DOI"));
            if (!requestedDoi.equals(returnedDoi)) {
                throw invalid("Crossref返回DOI与请求不一致");
            }
            String title = firstText(work.path("title"));
            if (title == null) throw invalid("Crossref响应缺少题名");
            var metadata = new CrossrefMetadataModels.Work(
                    returnedDoi, title, authors(work.path("author")),
                    firstText(work.path("container-title")),
                    publicationDate(work), text(work.path("type")),
                    text(work.path("publisher")));
            return new CrossrefMetadataModels.GatewayResult(
                    true, metadata, response.body().getBytes(StandardCharsets.UTF_8),
                    response.contentType(), TOOL_VERSION, response.requestCount(), false);
        } catch (CrossrefMetadataException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new CrossrefMetadataException(
                    "CROSSREF_RESPONSE_INVALID",
                    "Crossref响应不是合法JSON", exception);
        }
    }

    private List<String> authors(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String name = text(value.path("name"));
            if (name == null) {
                String given = text(value.path("given"));
                String family = text(value.path("family"));
                name = ((given == null ? "" : given) + " "
                        + (family == null ? "" : family)).strip();
            }
            if (!name.isBlank()) result.add(name);
        });
        return List.copyOf(result);
    }

    private String publicationDate(JsonNode work) {
        for (String field : List.of("published-print", "published", "published-online")) {
            JsonNode parts = work.path(field).path("date-parts");
            if (parts.isArray() && !parts.isEmpty() && parts.get(0).isArray()) {
                List<String> values = new ArrayList<>();
                parts.get(0).forEach(value -> {
                    if (value.isIntegralNumber()) values.add(value.asText());
                });
                if (!values.isEmpty()) return String.join("-", values);
            }
        }
        return null;
    }

    private String firstText(JsonNode values) {
        if (!values.isArray() || values.isEmpty()) return null;
        return text(values.get(0));
    }

    private String required(JsonNode value, String label) {
        String result = text(value);
        if (result == null) throw invalid("Crossref响应缺少" + label);
        return result;
    }

    private String text(JsonNode value) {
        if (!value.isTextual()) return null;
        String result = value.asText().strip();
        return result.isBlank() ? null : result;
    }

    private String normalizeDoi(String value) {
        String normalized = value == null ? "" : value.strip()
                .replaceFirst("(?i)^https?://(dx\\.)?doi\\.org/", "")
                .toLowerCase(Locale.ROOT);
        if (!normalized.matches(DOI_PATTERN)) {
            throw new IllegalArgumentException("DOI格式无效");
        }
        return normalized;
    }

    private String validateOptionalEmail(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.isBlank()
                && !normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("CROSSREF_MAILTO格式无效");
        }
        return normalized;
    }

    private String validateUserAgent(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 3 || normalized.length() > 120
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Crossref User-Agent格式无效");
        }
        return normalized;
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Crossref基础地址不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String contentType(MediaType value) {
        return value == null ? "application/json" : value.toString();
    }

    private long backoffMillis(RestClientResponseException exception, int attempt) {
        String retryAfter = exception.getResponseHeaders() == null
                ? null : exception.getResponseHeaders().getFirst("Retry-After");
        if (retryAfter != null) {
            try {
                return Math.min(5_000, Math.max(0, Long.parseLong(retryAfter) * 1_000));
            } catch (NumberFormatException ignored) {
                // Fall back to bounded exponential backoff.
            }
        }
        return Math.min(5_000, 250L << (attempt - 1));
    }

    private CrossrefMetadataException invalid(String message) {
        return new CrossrefMetadataException("CROSSREF_RESPONSE_INVALID", message);
    }

    private void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CrossrefMetadataException(
                    "CROSSREF_INTERRUPTED", "Crossref调用被中断", exception);
        }
    }

    private record HttpResult(
            String body, String contentType, int requestCount, boolean notFound) {}

    private static final class RequestThrottle {
        private final long intervalNanos;
        private long nextAllowedNanos;

        private RequestThrottle(Duration minimumInterval) {
            intervalNanos = Math.max(0, minimumInterval.toNanos());
        }

        private synchronized void acquire() {
            long now = System.nanoTime();
            long wait = nextAllowedNanos - now;
            if (wait > 0) {
                try {
                    long millis = wait / 1_000_000;
                    int nanos = (int) (wait % 1_000_000);
                    Thread.sleep(millis, nanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new CrossrefMetadataException(
                            "CROSSREF_INTERRUPTED", "Crossref限流等待被中断", exception);
                }
                now = System.nanoTime();
            }
            nextAllowedNanos = now + intervalNanos;
        }
    }

    public static class CrossrefMetadataException extends RuntimeException {
        private final String code;

        public CrossrefMetadataException(String code, String message) {
            super(message);
            this.code = code;
        }

        public CrossrefMetadataException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
