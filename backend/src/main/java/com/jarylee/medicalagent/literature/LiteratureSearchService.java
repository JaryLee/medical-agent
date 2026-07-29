package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.ObjectStorage;
import com.jarylee.medicalagent.literature.LiteratureSearchRepository.SearchData;
import com.jarylee.medicalagent.literature.PubMedSearchModels.SearchResult;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Service
public class LiteratureSearchService {
    public static final String RESULT_SCHEMA_VERSION = "pubmed-search-result/v1";
    private final PubMedSearchGateway gateway;
    private final LiteratureSearchRepository repository;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxResults;

    public LiteratureSearchService(
            PubMedSearchGateway gateway,
            LiteratureSearchRepository repository,
            ObjectStorage storage,
            ObjectMapper json,
            Clock clock,
            @Value("${medical.pubmed.max-results:20}") int maxResults) {
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("PUBMED_MAX_RESULTS必须在1到100之间");
        }
        this.gateway = gateway;
        this.repository = repository;
        this.storage = storage;
        this.json = json;
        this.clock = clock;
        this.maxResults = maxResults;
    }

    public SearchResult execute(
            UUID hospitalId, UUID projectId, UUID agentTaskId, SearchStrategy strategy) {
        requireConfirmed(strategy);
        UUID searchId = UUID.randomUUID();
        Instant started = clock.instant();
        SearchData running = new SearchData(
                searchId, hospitalId, projectId, agentTaskId, "PUBMED",
                strategy.originalResearchQuestion(), write(strategy.concepts()),
                strategy.pubmedQuery(), strategy.queryVersion(), write(strategy.filters()),
                "RUNNING", started, null, null, null, null, null, null,
                null, null, null, null);
        repository.create(running);
        String objectKey = null;
        try {
            var gatewayResult = gateway.search(strategy.pubmedQuery(), maxResults);
            Instant completed = clock.instant();
            String hash = sha256(gatewayResult.rawResponse());
            objectKey = objectKey(hospitalId, projectId, searchId);
            storage.put(objectKey, gatewayResult.rawResponse(), gatewayResult.rawContentType());
            SearchData complete = new SearchData(
                    searchId, hospitalId, projectId, agentTaskId, "PUBMED",
                    strategy.originalResearchQuestion(), write(strategy.concepts()),
                    strategy.pubmedQuery(), strategy.queryVersion(), write(strategy.filters()),
                    "COMPLETED", started, completed, gatewayResult.totalResultCount(),
                    gatewayResult.articles().size(), objectKey, hash,
                    gatewayResult.rawContentType(), gatewayResult.toolVersion(),
                    gatewayResult.externalRequestCount(), null, null);
            repository.complete(complete, gatewayResult.articles());
            return new SearchResult(
                    RESULT_SCHEMA_VERSION, searchId, "PUBMED", strategy.pubmedQuery(),
                    strategy.queryVersion(), completed, gatewayResult.totalResultCount(),
                    gatewayResult.articles().size(), gatewayResult.articles(), hash,
                    gatewayResult.rawContentType(), gatewayResult.toolVersion(),
                    gatewayResult.externalRequestCount(),
                    List.of(
                            "当前结果仅覆盖 PubMed，未覆盖 CNKI、万方、维普及灰色文献",
                            "摘要内容可能受版权保护，仅用于科研证据筛选",
                            "检索结果不构成创新性证明，仍需人工筛选和专家复核"
                    ));
        } catch (RuntimeException exception) {
            if (objectKey != null) {
                try {
                    storage.delete(objectKey);
                } catch (RuntimeException ignored) {
                    // Preserve the primary failure while the orphan can be reconciled operationally.
                }
            }
            repository.fail(hospitalId, searchId, errorCode(exception),
                    safeMessage(exception), clock.instant());
            if (exception instanceof NcbiPubMedSearchGateway.PubMedSearchException) {
                throw exception;
            }
            throw new NcbiPubMedSearchGateway.PubMedSearchException(
                    "PUBMED_SEARCH_FAILED", "PubMed检索执行失败", exception);
        }
    }

    private void requireConfirmed(SearchStrategy strategy) {
        if (strategy == null
                || !"CONFIRMED".equals(strategy.confirmationStatus())
                || strategy.pubmedQuery() == null
                || strategy.pubmedQuery().isBlank()) {
            throw new IllegalArgumentException("只有已确认检索策略可以执行PubMed检索");
        }
    }

    private String objectKey(UUID hospitalId, UUID projectId, UUID searchId) {
        return hospitalId + "/" + projectId + "/literature-search/"
                + searchId + "/ncbi-eutils-raw-v1.json";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("原始PubMed响应哈希失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("文献检索记录序列化失败", exception);
        }
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof NcbiPubMedSearchGateway.PubMedSearchException pubMed) {
            return pubMed.code();
        }
        return "PUBMED_SEARCH_FAILED";
    }

    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank() ? "PubMed检索失败" : value;
    }
}
