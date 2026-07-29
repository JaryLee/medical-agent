package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.ObjectStorage;
import com.jarylee.medicalagent.literature.ClinicalTrialsGovSearchGateway.ClinicalTrialsSearchException;
import com.jarylee.medicalagent.literature.ClinicalTrialsQueryService.ClinicalTrialsQuery;
import com.jarylee.medicalagent.literature.LiteratureSearchRepository.SearchData;
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
public class ClinicalTrialsSearchService {
    public static final String RESULT_SCHEMA_VERSION = "clinicaltrials-search-result/v1";

    private final ClinicalTrialsSearchGateway gateway;
    private final ClinicalTrialSearchRepository repository;
    private final ClinicalTrialsQueryService queryService;
    private final ObjectStorage storage;
    private final ObjectMapper json;
    private final Clock clock;
    private final int maxResults;

    public ClinicalTrialsSearchService(
            ClinicalTrialsSearchGateway gateway,
            ClinicalTrialSearchRepository repository,
            ClinicalTrialsQueryService queryService,
            ObjectStorage storage,
            ObjectMapper json,
            Clock clock,
            @Value("${medical.clinical-trials.max-results:20}") int maxResults) {
        if (maxResults < 1 || maxResults > 100) {
            throw new IllegalArgumentException("CLINICAL_TRIALS_MAX_RESULTS必须在1到100之间");
        }
        this.gateway = gateway;
        this.repository = repository;
        this.queryService = queryService;
        this.storage = storage;
        this.json = json;
        this.clock = clock;
        this.maxResults = maxResults;
    }

    public ClinicalTrialsSearchModels.SearchResult execute(
            UUID hospitalId, UUID projectId, UUID agentTaskId, SearchStrategy strategy) {
        ClinicalTrialsQuery query = queryService.build(strategy);
        UUID searchId = UUID.randomUUID();
        Instant started = clock.instant();
        SearchData running = new SearchData(
                searchId, hospitalId, projectId, agentTaskId, "CLINICAL_TRIALS_GOV",
                strategy.originalResearchQuestion(), write(query.concepts()),
                query.query(), query.queryVersion(), write(List.of()),
                "RUNNING", started, null, null, null, null, null, null,
                null, null, null, null);
        repository.create(running);
        String objectKey = null;
        try {
            var gatewayResult = gateway.search(query.query(), maxResults);
            Instant completed = clock.instant();
            String hash = sha256(gatewayResult.rawResponse());
            objectKey = objectKey(hospitalId, projectId, searchId);
            storage.put(objectKey, gatewayResult.rawResponse(), gatewayResult.rawContentType());
            SearchData complete = new SearchData(
                    searchId, hospitalId, projectId, agentTaskId, "CLINICAL_TRIALS_GOV",
                    strategy.originalResearchQuestion(), write(query.concepts()),
                    query.query(), query.queryVersion(), write(List.of()),
                    "COMPLETED", started, completed, gatewayResult.totalResultCount(),
                    gatewayResult.trials().size(), objectKey, hash,
                    gatewayResult.rawContentType(), gatewayResult.toolVersion(),
                    gatewayResult.externalRequestCount(), null, null);
            repository.complete(complete, gatewayResult.trials());
            return new ClinicalTrialsSearchModels.SearchResult(
                    RESULT_SCHEMA_VERSION, searchId, "CLINICAL_TRIALS_GOV",
                    "TRIAL_REGISTRY", query.query(), query.queryVersion(), completed,
                    gatewayResult.totalResultCount(), gatewayResult.trials().size(),
                    gatewayResult.trials(), hash, gatewayResult.rawContentType(),
                    gatewayResult.toolVersion(), gatewayResult.externalRequestCount(),
                    gatewayResult.dataVersion(), gatewayResult.cacheHit(),
                    List.of(
                            "ClinicalTrials.gov记录是研究注册信息，不等同于同行评议发表证据",
                            "注册状态、计划终点和入组信息可能更新，使用时需复核检索日期与数据版本",
                            "当前检索未覆盖WHO ICTRP、ChiCTR及其他国家或地区注册平台",
                            "检索概念未由模型翻译，中文概念可能降低英文注册记录召回率"
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
            if (exception instanceof ClinicalTrialsSearchException) throw exception;
            throw new ClinicalTrialsSearchException(
                    "CLINICAL_TRIALS_SEARCH_FAILED",
                    "ClinicalTrials.gov检索执行失败", exception);
        }
    }

    private String objectKey(UUID hospitalId, UUID projectId, UUID searchId) {
        return hospitalId + "/" + projectId + "/literature-search/"
                + searchId + "/clinicaltrials-api-v2-raw.json";
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("ClinicalTrials.gov原始响应哈希失败", exception);
        }
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("临床试验检索记录序列化失败", exception);
        }
    }

    private String errorCode(RuntimeException exception) {
        if (exception instanceof ClinicalTrialsSearchException clinicalTrials) {
            return clinicalTrials.code();
        }
        return "CLINICAL_TRIALS_SEARCH_FAILED";
    }

    private String safeMessage(RuntimeException exception) {
        String value = exception.getMessage();
        return value == null || value.isBlank()
                ? "ClinicalTrials.gov检索失败" : value;
    }
}
