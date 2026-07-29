package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.ByteArrayInputStream;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Component
@ConditionalOnProperty(name = "medical.pubmed.mode", havingValue = "live")
public class NcbiPubMedSearchGateway implements PubMedSearchGateway {
    public static final String TOOL_VERSION = "ncbi-eutils/v1";
    private static final Set<Integer> RETRYABLE_STATUS =
            Set.of(429, 500, 502, 503, 504);

    private final ObjectMapper json;
    private final RestClient client;
    private final String tool;
    private final String email;
    private final String apiKey;
    private final RequestThrottle throttle;
    private final int maxAttempts;

    public NcbiPubMedSearchGateway(
            ObjectMapper json,
            RestClient.Builder builder,
            @Value("${medical.pubmed.base-url:https://eutils.ncbi.nlm.nih.gov/entrez/eutils}")
            String baseUrl,
            @Value("${medical.pubmed.tool:medical_research_agent}") String tool,
            @Value("${medical.pubmed.email:}") String email,
            @Value("${medical.pubmed.api-key:}") String apiKey,
            @Value("${medical.pubmed.connect-timeout:5s}") Duration connectTimeout,
            @Value("${medical.pubmed.read-timeout:30s}") Duration readTimeout,
            @Value("${medical.pubmed.max-attempts:3}") int maxAttempts) {
        this(json, builder, baseUrl, tool, email, apiKey, connectTimeout, readTimeout,
                maxAttempts, apiKey == null || apiKey.isBlank()
                        ? Duration.ofMillis(350) : Duration.ofMillis(110));
    }

    NcbiPubMedSearchGateway(
            ObjectMapper json, RestClient.Builder builder, String baseUrl,
            String tool, String email, String apiKey,
            Duration connectTimeout, Duration readTimeout,
            int maxAttempts, Duration minimumInterval) {
        this.json = json;
        this.tool = validateTool(tool);
        this.email = validateEmail(email);
        this.apiKey = apiKey == null ? "" : apiKey.strip();
        if (maxAttempts < 1 || maxAttempts > 5) {
            throw new IllegalArgumentException("PubMed最大重试次数必须在1到5之间");
        }
        this.maxAttempts = maxAttempts;
        this.throttle = new RequestThrottle(minimumInterval);
        var requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(connectTimeout);
        requestFactory.setReadTimeout(readTimeout);
        this.client = builder
                .baseUrl(stripTrailingSlash(baseUrl))
                .requestFactory(requestFactory)
                .build();
    }

    @Override
    public PubMedSearchModels.GatewayResult search(String query, int maxResults) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("PubMed检索式不能为空");
        }
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("PubMed单次返回数量必须在1到100之间");
        }
        String esearchRaw = post("/esearch.fcgi", form(
                "db", "pubmed",
                "term", query,
                "retmode", "json",
                "retmax", Integer.toString(maxResults),
                "usehistory", "y",
                "sort", "relevance"));
        JsonNode esearch = readJson(esearchRaw, "ESearch");
        JsonNode searchResult = esearch.path("esearchresult");
        if (!searchResult.isObject()) {
            throw new PubMedSearchException("PUBMED_RESPONSE_INVALID", "ESearch响应缺少结果");
        }
        long total = parseLong(searchResult.path("count").asText(), "ESearch结果数量");
        List<String> ids = textValues(searchResult.path("idlist"));
        String webEnv = text(searchResult, "webenv");
        String queryKey = text(searchResult, "querykey");
        if (ids.isEmpty()) {
            return result(total, List.of(), esearchRaw, null, null, 1, webEnv, queryKey);
        }

        String joinedIds = String.join(",", ids);
        String summaryRaw = post("/esummary.fcgi", form(
                "db", "pubmed", "id", joinedIds, "retmode", "json", "version", "2.0"));
        JsonNode summary = readJson(summaryRaw, "ESummary");
        String fetchRaw = post("/efetch.fcgi", form(
                "db", "pubmed", "id", joinedIds, "retmode", "xml", "rettype", "abstract"));
        List<PubMedSearchModels.Article> articles =
                parseAndValidateArticles(fetchRaw, summary, new HashSet<>(ids));
        if (articles.size() != ids.size()) {
            throw new PubMedSearchException(
                    "PUBMED_MAPPING_MISMATCH", "PubMed详情数量与ESearch PMID数量不一致");
        }
        return result(total, articles, esearchRaw, summaryRaw, fetchRaw,
                3, webEnv, queryKey);
    }

    private PubMedSearchModels.GatewayResult result(
            long total, List<PubMedSearchModels.Article> articles,
            String searchRaw, String summaryRaw, String fetchRaw,
            int requestCount, String webEnv, String queryKey) {
        ObjectNode envelope = json.createObjectNode();
        envelope.put("schemaVersion", "ncbi-eutils-raw/v1");
        envelope.set("esearch", readJson(searchRaw, "ESearch"));
        if (summaryRaw != null) envelope.set("esummary", readJson(summaryRaw, "ESummary"));
        if (fetchRaw != null) envelope.put("efetchXml", fetchRaw);
        try {
            return new PubMedSearchModels.GatewayResult(
                    total, List.copyOf(articles), json.writeValueAsBytes(envelope),
                    "application/json", TOOL_VERSION, requestCount, webEnv, queryKey);
        } catch (Exception exception) {
            throw new PubMedSearchException(
                    "PUBMED_RESPONSE_INVALID", "PubMed原始响应封装失败", exception);
        }
    }

    private List<PubMedSearchModels.Article> parseAndValidateArticles(
            String xml, JsonNode summary, Set<String> expectedIds) {
        Document document = parseXml(xml);
        NodeList articleNodes = document.getElementsByTagName("PubmedArticle");
        List<PubMedSearchModels.Article> articles = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < articleNodes.getLength(); index++) {
            Element pubmedArticle = (Element) articleNodes.item(index);
            Element citation = first(pubmedArticle, "MedlineCitation");
            String pmid = directOrDescendantText(citation, "PMID");
            if (!expectedIds.contains(pmid) || !seen.add(pmid)) {
                throw new PubMedSearchException(
                        "PUBMED_MAPPING_MISMATCH", "PubMed返回未知或重复PMID");
            }
            Element article = first(citation, "Article");
            String title = descendantText(article, "ArticleTitle");
            JsonNode summaryRecord = summary.path("result").path(pmid);
            String summaryTitle = summaryRecord.path("title").asText("");
            if (summaryRecord.isMissingNode() || summaryTitle.isBlank()
                    || !normalizeTitle(title).equals(normalizeTitle(summaryTitle))) {
                throw new PubMedSearchException(
                        "PUBMED_MAPPING_MISMATCH", "PMID与标题映射校验失败: " + pmid);
            }
            List<String> authors = authors(article);
            String journal = descendantText(article, "Title");
            String publicationDate = publicationDate(article);
            String abstractText = abstractText(article);
            String doi = articleId(pubmedArticle, "doi");
            articles.add(new PubMedSearchModels.Article(
                    pmid, emptyToNull(doi), title, authors, journal, publicationDate,
                    abstractText, abstractText.isBlank() ? "METADATA_ONLY" : "ABSTRACT_ONLY",
                    true, "PUBMED_EUTILS"));
        }
        return List.copyOf(articles);
    }

    private String post(String path, MultiValueMap<String, String> values) {
        addCommon(values);
        RestClientResponseException last = null;
        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            throttle.acquire();
            try {
                String response = client.post()
                        .uri(path)
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .body(values)
                        .retrieve()
                        .body(String.class);
                if (response == null || response.isBlank()) {
                    throw new PubMedSearchException(
                            "PUBMED_RESPONSE_INVALID", "PubMed返回空响应");
                }
                return response;
            } catch (RestClientResponseException exception) {
                last = exception;
                int status = exception.getStatusCode().value();
                if (!RETRYABLE_STATUS.contains(status) || attempt == maxAttempts) {
                    throw new PubMedSearchException(
                            "PUBMED_HTTP_ERROR", "PubMed调用失败，HTTP " + status);
                }
                pause(backoffMillis(exception, attempt));
            } catch (PubMedSearchException exception) {
                throw exception;
            } catch (Exception exception) {
                if (attempt == maxAttempts) {
                    throw new PubMedSearchException(
                            "PUBMED_UNAVAILABLE", "PubMed服务不可用", exception);
                }
                pause(250L << (attempt - 1));
            }
        }
        throw new PubMedSearchException(
                "PUBMED_UNAVAILABLE", "PubMed服务不可用", last);
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

    private void addCommon(MultiValueMap<String, String> values) {
        values.add("tool", tool);
        values.add("email", email);
        if (!apiKey.isBlank()) values.add("api_key", apiKey);
    }

    private MultiValueMap<String, String> form(String... values) {
        MultiValueMap<String, String> result = new LinkedMultiValueMap<>();
        for (int index = 0; index < values.length; index += 2) {
            result.add(values[index], values[index + 1]);
        }
        return result;
    }

    private JsonNode readJson(String value, String operation) {
        try {
            JsonNode parsed = json.readTree(value);
            if (parsed.has("error")) {
                throw new PubMedSearchException(
                        "PUBMED_API_ERROR", operation + "返回错误");
            }
            return parsed;
        } catch (PubMedSearchException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new PubMedSearchException(
                    "PUBMED_RESPONSE_INVALID", operation + "响应不是合法JSON", exception);
        }
    }

    private Document parseXml(String value) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", false);
            factory.setFeature(
                    "http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setEntityResolver((publicId, systemId) ->
                    new InputSource(new StringReader("")));
            return builder.parse(
                    new ByteArrayInputStream(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception exception) {
            throw new PubMedSearchException(
                    "PUBMED_RESPONSE_INVALID", "EFetch响应不是安全合法XML", exception);
        }
    }

    private Element first(Element parent, String name) {
        NodeList values = parent.getElementsByTagName(name);
        if (values.getLength() == 0) {
            throw new PubMedSearchException(
                    "PUBMED_RESPONSE_INVALID", "EFetch响应缺少" + name);
        }
        return (Element) values.item(0);
    }

    private String descendantText(Element parent, String name) {
        NodeList values = parent.getElementsByTagName(name);
        return values.getLength() == 0 ? "" : values.item(0).getTextContent().strip();
    }

    private String directOrDescendantText(Element parent, String name) {
        return descendantText(parent, name);
    }

    private List<String> authors(Element article) {
        NodeList lists = article.getElementsByTagName("AuthorList");
        if (lists.getLength() == 0) return List.of();
        NodeList values = ((Element) lists.item(0)).getElementsByTagName("Author");
        List<String> authors = new ArrayList<>();
        for (int index = 0; index < values.getLength(); index++) {
            Element author = (Element) values.item(index);
            String collective = descendantText(author, "CollectiveName");
            if (!collective.isBlank()) {
                authors.add(collective);
                continue;
            }
            String lastName = descendantText(author, "LastName");
            String initials = descendantText(author, "Initials");
            String value = (lastName + " " + initials).strip();
            if (!value.isBlank()) authors.add(value);
        }
        return List.copyOf(authors);
    }

    private String abstractText(Element article) {
        NodeList values = article.getElementsByTagName("AbstractText");
        List<String> parts = new ArrayList<>();
        for (int index = 0; index < values.getLength(); index++) {
            Node node = values.item(index);
            String text = node.getTextContent().strip();
            if (!text.isBlank()) {
                String label = node instanceof Element element
                        ? element.getAttribute("Label") : "";
                parts.add(label.isBlank() ? text : label + ": " + text);
            }
        }
        return String.join("\n", parts);
    }

    private String publicationDate(Element article) {
        NodeList dates = article.getElementsByTagName("ArticleDate");
        Element date = dates.getLength() > 0
                ? (Element) dates.item(0)
                : first(first(article, "JournalIssue"), "PubDate");
        List<String> parts = new ArrayList<>();
        for (String name : List.of("Year", "Month", "Day", "MedlineDate")) {
            String value = descendantText(date, name);
            if (!value.isBlank()) parts.add(value);
        }
        return String.join("-", parts);
    }

    private String articleId(Element pubmedArticle, String type) {
        NodeList values = pubmedArticle.getElementsByTagName("ArticleId");
        for (int index = 0; index < values.getLength(); index++) {
            Element value = (Element) values.item(index);
            if (type.equalsIgnoreCase(value.getAttribute("IdType"))) {
                return value.getTextContent().strip();
            }
        }
        return "";
    }

    private List<String> textValues(JsonNode values) {
        if (!values.isArray()) return List.of();
        List<String> result = new ArrayList<>();
        values.forEach(value -> {
            if (value.isTextual() && value.asText().matches("\\d+")) {
                result.add(value.asText());
            }
        });
        return List.copyOf(result);
    }

    private String text(JsonNode parent, String field) {
        String value = parent.path(field).asText("");
        return value.isBlank() ? null : value;
    }

    private long parseLong(String value, String label) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException exception) {
            throw new PubMedSearchException(
                    "PUBMED_RESPONSE_INVALID", label + "无效", exception);
        }
    }

    private String normalizeTitle(String value) {
        return value.replaceAll("<[^>]+>", " ")
                .replaceAll("\\s+", " ")
                .replaceAll("[.。]+$", "")
                .strip().toLowerCase(Locale.ROOT);
    }

    private String emptyToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    private String validateTool(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{3,80}")) {
            throw new IllegalArgumentException("PUBMED_TOOL格式无效");
        }
        return value;
    }

    private String validateEmail(String value) {
        String normalized = value == null ? "" : value.strip();
        if (!normalized.matches("[^\\s@]+@[^\\s@]+\\.[^\\s@]+")) {
            throw new IllegalArgumentException("真实PubMed模式必须配置有效PUBMED_EMAIL");
        }
        return normalized;
    }

    private String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PubMed基础地址不能为空");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void pause(long milliseconds) {
        try {
            Thread.sleep(milliseconds);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new PubMedSearchException(
                    "PUBMED_INTERRUPTED", "PubMed调用被中断", exception);
        }
    }

    static final class RequestThrottle {
        private final long intervalNanos;
        private long nextAllowedNanos;

        RequestThrottle(Duration minimumInterval) {
            this.intervalNanos = Math.max(0, minimumInterval.toNanos());
        }

        synchronized void acquire() {
            long now = System.nanoTime();
            long wait = nextAllowedNanos - now;
            if (wait > 0) {
                try {
                    long millis = wait / 1_000_000;
                    int nanos = (int) (wait % 1_000_000);
                    Thread.sleep(millis, nanos);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new PubMedSearchException(
                            "PUBMED_INTERRUPTED", "PubMed限流等待被中断", exception);
                }
                now = System.nanoTime();
            }
            nextAllowedNanos = now + intervalNanos;
        }
    }

    public static class PubMedSearchException extends RuntimeException {
        private final String code;

        public PubMedSearchException(String code, String message) {
            super(message);
            this.code = code;
        }

        public PubMedSearchException(String code, String message, Throwable cause) {
            super(message, cause);
            this.code = code;
        }

        public String code() {
            return code;
        }
    }
}
