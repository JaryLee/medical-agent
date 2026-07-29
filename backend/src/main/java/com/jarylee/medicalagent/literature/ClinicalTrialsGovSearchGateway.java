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
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "medical.clinical-trials.mode", havingValue = "live")
public class ClinicalTrialsGovSearchGateway implements ClinicalTrialsSearchGateway {
    public static final String TOOL_VERSION = "clinicaltrials-api-v2";
    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(429, 500, 502, 503, 504);

    private final ObjectMapper json;
    private final RestClient client;
    private final int maxAttempts;
    private final RequestThrottle throttle;
    private final Cache<String, ClinicalTrialsSearchModels.GatewayResult> cache;

    public ClinicalTrialsGovSearchGateway(
            ObjectMapper json,
            RestClient.Builder builder,
            @Value("${medical.clinical-trials.base-url:https://clinicaltrials.gov/api/v2}")
            String baseUrl,
            @Value("${medical.clinical-trials.user-agent:medical-research-agent/1.0}")
            String userAgent,
            @Value("${medical.clinical-trials.connect-timeout:5s}")
            Duration connectTimeout,
            @Value("${medical.clinical-trials.read-timeout:30s}")
            Duration readTimeout,
            @Value("${medical.clinical-trials.max-attempts:3}") int maxAttempts,
            @Value("${medical.clinical-trials.cache-ttl:12h}") Duration cacheTtl) {
        this(json, builder, baseUrl, userAgent, connectTimeout, readTimeout,
                maxAttempts, cacheTtl, Duration.ofMillis(100));
    }

    ClinicalTrialsGovSearchGateway(
            ObjectMapper json, RestClient.Builder builder, String baseUrl, String userAgent,
            Duration connectTimeout, Duration readTimeout, int maxAttempts,
            Duration cacheTtl, Duration minimumInterval) {
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("ClinicalTrials.gov最大重试次数必须在1到5之间");
        }
        if (cacheTtl.isNegative() || cacheTtl.isZero() || cacheTtl.compareTo(Duration.ofDays(1)) > 0) {
            throw new IllegalArgumentException("ClinicalTrials.gov缓存时间必须在0到24小时之间");
        }
        this.json = json;
        this.maxAttempts = maxAttempts;
        this.throttle = new RequestThrottle(minimumInterval);
        this.cache = Caffeine.newBuilder()
                .maximumSize(200)
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
    public ClinicalTrialsSearchModels.GatewayResult search(String query, int maxResults) {
        String normalized = validateQuery(query);
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("ClinicalTrials.gov单次返回数量必须在1到100之间");
        }
        String cacheKey = normalized + "\n" + maxResults;
        var cached = cache.getIfPresent(cacheKey);
        if (cached != null) {
            return new ClinicalTrialsSearchModels.GatewayResult(
                    cached.totalResultCount(), cached.trials(), cached.rawResponse(),
                    cached.rawContentType(), cached.toolVersion(), 0,
                    cached.dataVersion(), true);
        }

        HttpResult http = get(normalized, maxResults);
        ClinicalTrialsSearchModels.GatewayResult result = parse(
                http.body(), http.contentType(), http.requestCount());
        cache.put(cacheKey, result);
        return result;
    }

    private HttpResult get(String query, int maxResults) {
        RestClientResponseException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle.acquire();
            try {
                var response = client.get()
                        .uri(uri -> uri.path("/studies")
                                .queryParam("query.term", query)
                                .queryParam("pageSize", maxResults)
                                .queryParam("countTotal", true)
                                .queryParam("format", "json")
                                .build())
                        .accept(MediaType.APPLICATION_JSON)
                        .retrieve()
                        .toEntity(String.class);
                String body = response.getBody();
                if (body == null || body.isBlank()) {
                    throw new ClinicalTrialsSearchException(
                            "CLINICAL_TRIALS_RESPONSE_INVALID",
                            "ClinicalTrials.gov返回空响应");
                }
                String contentType = response.getHeaders().getContentType() == null
                        ? "application/json"
                        : response.getHeaders().getContentType().toString();
                return new HttpResult(body, contentType, attempt);
            } catch (RestClientResponseException exception) {
                last = exception;
                int status = exception.getStatusCode().value();
                if (!RETRYABLE_STATUS.contains(status) || attempt == maxAttempts) {
                    throw new ClinicalTrialsSearchException(
                            "CLINICAL_TRIALS_HTTP_ERROR",
                            "ClinicalTrials.gov调用失败，HTTP " + status);
                }
                pause(backoffMillis(exception, attempt));
            } catch (ClinicalTrialsSearchException exception) {
                throw exception;
            } catch (RuntimeException exception) {
                if (attempt == maxAttempts) {
                    throw new ClinicalTrialsSearchException(
                            "CLINICAL_TRIALS_UNAVAILABLE",
                            "ClinicalTrials.gov服务不可用", exception);
                }
                pause(Math.min(5_000, 250L << (attempt - 1)));
            }
        }
        throw new ClinicalTrialsSearchException(
                "CLINICAL_TRIALS_UNAVAILABLE", "ClinicalTrials.gov服务不可用", last);
    }

    private ClinicalTrialsSearchModels.GatewayResult parse(
            String raw, String contentType, int requestCount) {
        try {
            JsonNode root = json.readTree(raw);
            JsonNode studies = root.path("studies");
            if (!studies.isArray()) {
                throw invalid("ClinicalTrials.gov响应缺少studies数组");
            }
            long total = root.path("totalCount").asLong(-1);
            if (total < 0 || total < studies.size()) {
                throw invalid("ClinicalTrials.gov结果数量无效");
            }
            List<ClinicalTrialsSearchModels.Trial> trials = new ArrayList<>();
            Set<String> ids = new HashSet<>();
            String dataVersion = null;
            for (JsonNode study : studies) {
                var trial = parseTrial(study);
                if (!ids.add(trial.nctId())) {
                    throw invalid("ClinicalTrials.gov返回重复NCT ID");
                }
                trials.add(trial);
                String version = text(study.at("/derivedSection/miscInfoModule/versionHolder"));
                if (version != null && (dataVersion == null || version.compareTo(dataVersion) > 0)) {
                    dataVersion = version;
                }
            }
            return new ClinicalTrialsSearchModels.GatewayResult(
                    total, List.copyOf(trials), raw.getBytes(StandardCharsets.UTF_8),
                    contentType, TOOL_VERSION, requestCount, dataVersion, false);
        } catch (ClinicalTrialsSearchException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new ClinicalTrialsSearchException(
                    "CLINICAL_TRIALS_RESPONSE_INVALID",
                    "ClinicalTrials.gov响应不是合法JSON", exception);
        }
    }

    private ClinicalTrialsSearchModels.Trial parseTrial(JsonNode study) {
        JsonNode protocol = study.path("protocolSection");
        JsonNode identification = protocol.path("identificationModule");
        JsonNode status = protocol.path("statusModule");
        JsonNode design = protocol.path("designModule");
        String nctId = required(identification.path("nctId"), "NCT ID");
        if (!nctId.matches("NCT\\d{8}")) {
            throw invalid("ClinicalTrials.gov返回无效NCT ID");
        }
        String briefTitle = required(identification.path("briefTitle"), "简要题名");
        String overallStatus = required(status.path("overallStatus"), "总体状态");
        String studyType = required(design.path("studyType"), "研究类型");

        return new ClinicalTrialsSearchModels.Trial(
                nctId,
                briefTitle,
                text(identification.path("officialTitle")),
                overallStatus,
                studyType,
                textList(design.path("phases")),
                textList(protocol.at("/conditionsModule/conditions")),
                interventions(protocol.at("/armsInterventionsModule/interventions")),
                text(protocol.at("/descriptionModule/briefSummary")),
                fieldValues(protocol.at("/outcomesModule/primaryOutcomes"), "measure"),
                text(protocol.at("/sponsorCollaboratorsModule/leadSponsor/name")),
                text(status.at("/startDateStruct/date")),
                text(status.at("/completionDateStruct/date")),
                integer(design.at("/enrollmentInfo/count")),
                countries(protocol.at("/contactsLocationsModule/locations")),
                study.path("hasResults").asBoolean(false),
                study.path("hasResults").asBoolean(false)
                        ? "REGISTRY_RESULTS_AVAILABLE" : "REGISTRY_METADATA_ONLY",
                true,
                "CLINICAL_TRIALS_GOV",
                linkedPmids(protocol.at("/referencesModule/references"))
        );
    }

    private List<String> interventions(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String name = text(value.path("name"));
            if (name != null) {
                String type = text(value.path("type"));
                result.add(type == null ? name : type + ": " + name);
            }
        });
        return List.copyOf(result);
    }

    private List<String> countries(JsonNode values) {
        if (!values.isArray()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            String country = text(value.path("country"));
            if (country != null) result.add(country);
        });
        return List.copyOf(result);
    }

    private List<String> linkedPmids(JsonNode values) {
        if (!values.isArray()) return List.of();
        Set<String> result = new LinkedHashSet<>();
        values.forEach(value -> {
            String pmid = text(value.path("pmid"));
            if (pmid != null && pmid.matches("\\d+")) result.add(pmid);
        });
        return List.copyOf(result);
    }

    private List<String> fieldValues(JsonNode values, String field) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            String text = text(value.path(field));
            if (text != null) result.add(text);
        });
        return List.copyOf(result);
    }

    private List<String> textList(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && !value.asText().isBlank()) {
                result.add(value.asText().strip());
            }
        });
        return List.copyOf(result);
    }

    private Integer integer(JsonNode value) {
        return value.isIntegralNumber() && value.canConvertToInt() ? value.asInt() : null;
    }

    private String required(JsonNode value, String label) {
        String text = text(value);
        if (text == null) throw invalid("ClinicalTrials.gov响应缺少" + label);
        return text;
    }

    private String text(JsonNode value) {
        if (!value.isTextual()) return null;
        String text = value.asText().strip();
        return text.isBlank() ? null : text;
    }

    private ClinicalTrialsSearchException invalid(String message) {
        return new ClinicalTrialsSearchException(
                "CLINICAL_TRIALS_RESPONSE_INVALID", message);
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

    private String validateQuery(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ClinicalTrials.gov检索式不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > 4000) {
            throw new IllegalArgumentException("ClinicalTrials.gov检索式不能超过4000字");
        }
        for (int index = 0; index < normalized.length(); index++) {
            if (Character.isISOControl(normalized.charAt(index))) {
                throw new IllegalArgumentException("ClinicalTrials.gov检索式包含非法控制字符");
            }
        }
        return normalized;
    }

    private String validateUserAgent(String value) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.length() < 3 || normalized.length() > 120
                || normalized.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("ClinicalTrials.gov User-Agent格式无效");
        }
        return normalized;
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("ClinicalTrials.gov基础地址不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new ClinicalTrialsSearchException(
                    "CLINICAL_TRIALS_INTERRUPTED",
                    "ClinicalTrials.gov调用被中断", exception);
        }
    }

    private record HttpResult(String body, String contentType, int requestCount) {}

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
                    throw new ClinicalTrialsSearchException(
                            "CLINICAL_TRIALS_INTERRUPTED",
                            "ClinicalTrials.gov限流等待被中断", exception);
                }
                now = System.nanoTime();
            }
            nextAllowedNanos = now + intervalNanos;
        }
    }

    public static class ClinicalTrialsSearchException extends RuntimeException {
        private final String code;

        public ClinicalTrialsSearchException(String code, String message) {
            super(message);
            this.code = code;
        }

        public ClinicalTrialsSearchException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
