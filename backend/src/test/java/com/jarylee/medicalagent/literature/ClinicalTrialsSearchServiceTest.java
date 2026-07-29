package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.MemoryObjectStorage;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClinicalTrialsSearchServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T02:00:00Z"), ZoneOffset.UTC);

    @Test
    void persistsReplayableRegistryResponseAndMatchingHash() throws Exception {
        byte[] raw = "{\"studies\":[]}".getBytes();
        ClinicalTrialsSearchGateway gateway = (query, max) ->
                new ClinicalTrialsSearchModels.GatewayResult(
                        1, List.of(trial()), raw, "application/json",
                        "clinicaltrials-api-v2", 1, "2026-07-27", false);
        var repository = new MemoryClinicalTrialSearchRepository();
        var storage = new MemoryObjectStorage();
        var service = new ClinicalTrialsSearchService(
                gateway, repository, new ClinicalTrialsQueryService(),
                storage, json, clock, 20);

        var result = service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), strategy());

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(result.rawResponseSha256()).isEqualTo(expectedHash);
        assertThat(result.sourceType()).isEqualTo("TRIAL_REGISTRY");
        assertThat(result.records()).singleElement()
                .extracting(ClinicalTrialsSearchModels.Trial::nctId)
                .isEqualTo("NCT03594110");
        var persisted = repository.all().getFirst();
        assertThat(persisted.status()).isEqualTo("COMPLETED");
        assertThat(storage.get(persisted.rawObjectKey())).isEqualTo(raw);
    }

    @Test
    void recordsExplicitFailureWithoutInventingRegistryResults() {
        ClinicalTrialsSearchGateway gateway = (query, max) -> {
            throw new ClinicalTrialsGovSearchGateway.ClinicalTrialsSearchException(
                    "CLINICAL_TRIALS_HTTP_ERROR",
                    "ClinicalTrials.gov调用失败，HTTP 503");
        };
        var repository = new MemoryClinicalTrialSearchRepository();
        var service = new ClinicalTrialsSearchService(
                gateway, repository, new ClinicalTrialsQueryService(),
                new MemoryObjectStorage(), json, clock, 20);

        assertThatThrownBy(() -> service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), strategy()))
                .isInstanceOf(
                        ClinicalTrialsGovSearchGateway.ClinicalTrialsSearchException.class)
                .hasMessageContaining("503");
        assertThat(repository.all()).singleElement().satisfies(search -> {
            assertThat(search.status()).isEqualTo("FAILED");
            assertThat(search.errorCode()).isEqualTo("CLINICAL_TRIALS_HTTP_ERROR");
            assertThat(search.returnedResultCount()).isNull();
        });
    }

    private SearchStrategy strategy() {
        return new SearchStrategy(
                "search-strategy/v1", "deterministic-peco/v1", "pubmed-query/v1",
                "CONFIRMED", "测试问题", List.of("PUBMED"),
                List.of(
                        new SearchConcept("POPULATION", "人群",
                                List.of("diabetes"), true),
                        new SearchConcept("EXPOSURE", "暴露",
                                List.of("empagliflozin"), true),
                        new SearchConcept("OUTCOME", "结局",
                                List.of("kidney function"), true)),
                "unused", "unused", List.of(), List.of());
    }

    private ClinicalTrialsSearchModels.Trial trial() {
        return new ClinicalTrialsSearchModels.Trial(
                "NCT03594110", "Brief title", "Official title",
                "COMPLETED", "INTERVENTIONAL", List.of("PHASE3"),
                List.of("Kidney Disease"), List.of("DRUG: Empagliflozin"),
                "Summary", List.of("Primary outcome"), "Sponsor",
                "2019-01-29", "2022-07-05", 6609, List.of("United Kingdom"),
                true, "REGISTRY_RESULTS_AVAILABLE", true,
                "CLINICAL_TRIALS_GOV", List.of("36331190"));
    }
}
