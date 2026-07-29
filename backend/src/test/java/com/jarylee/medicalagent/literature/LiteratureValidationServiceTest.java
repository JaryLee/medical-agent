package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.MemoryObjectStorage;
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

class LiteratureValidationServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T05:00:00Z"), ZoneOffset.UTC);

    @Test
    void validatesCitationsPersistsRawEvidenceAndLinksRegistryPmids() throws Exception {
        CrossrefMetadataGateway gateway = doi -> {
            if (doi.endsWith("missing")) {
                return new CrossrefMetadataModels.GatewayResult(
                        false, null, "{}".getBytes(), "application/json",
                        "crossref-test/v1", 1, false);
            }
            var work = new CrossrefMetadataModels.Work(
                    "10.1056/nejmoa2204233",
                    "Empagliflozin in Patients with Chronic Kidney Disease",
                    List.of("The EMPA-KIDNEY Collaborative Group"),
                    "New England Journal of Medicine", "2023-01-12",
                    "journal-article", "Massachusetts Medical Society");
            return new CrossrefMetadataModels.GatewayResult(
                    true, work, "{\"status\":\"ok\"}".getBytes(),
                    "application/json", "crossref-test/v1", 1, false);
        };
        var repository = new MemoryLiteratureValidationRepository();
        var storage = new MemoryObjectStorage();
        var service = new LiteratureValidationService(
                gateway, repository, storage, json, clock);

        var result = service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                pubmed(), clinicalTrials());

        assertThat(result.totalCount()).isEqualTo(3);
        assertThat(result.verifiedCount()).isEqualTo(1);
        assertThat(result.crossrefNotFoundCount()).isEqualTo(1);
        assertThat(result.doiNotAvailableCount()).isEqualTo(1);
        assertThat(result.evidenceLinks()).hasSize(2);
        assertThat(result.evidenceLinks()).extracting(
                        LiteratureValidationModels.EvidenceLink::status)
                .containsExactly("RESOLVED", "UNRESOLVED_PUBMED");
        var persisted = repository.all().getFirst();
        assertThat(persisted.status()).isEqualTo("COMPLETED");
        byte[] raw = storage.get(persisted.rawObjectKey());
        assertThat(result.rawResponseSha256()).isEqualTo(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw)));
    }

    @Test
    void recordsExplicitFailureWhenCrossrefIsUnavailable() {
        CrossrefMetadataGateway gateway = doi -> {
            throw new CrossrefRestMetadataGateway.CrossrefMetadataException(
                    "CROSSREF_UNAVAILABLE", "Crossref 服务不可用");
        };
        var repository = new MemoryLiteratureValidationRepository();
        var service = new LiteratureValidationService(
                gateway, repository, new MemoryObjectStorage(), json, clock);

        assertThatThrownBy(() -> service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                pubmed(), clinicalTrials()))
                .isInstanceOf(
                        CrossrefRestMetadataGateway.CrossrefMetadataException.class);
        assertThat(repository.all()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo("FAILED");
            assertThat(record.errorCode()).isEqualTo("CROSSREF_UNAVAILABLE");
        });
    }

    private PubMedSearchModels.SearchResult pubmed() {
        return new PubMedSearchModels.SearchResult(
                "pubmed-search-result/v1", UUID.randomUUID(), "PUBMED", "query",
                "pubmed-query/v1", clock.instant(), 3, 3,
                List.of(
                        article("36331190", "10.1056/NEJMoa2204233",
                                "Empagliflozin in Patients with Chronic Kidney Disease"),
                        article("20000002", "10.9999/missing", "Missing"),
                        article("20000003", null, "No DOI")),
                "hash", "application/json", "ncbi-test/v1", 1, List.of());
    }

    private PubMedSearchModels.Article article(String pmid, String doi, String title) {
        return new PubMedSearchModels.Article(
                pmid, doi, title, List.of("The EMPA-KIDNEY Collaborative Group"),
                "New England Journal of Medicine", "2023 Jan 12", "Abstract",
                "ABSTRACT_ONLY", true, "PUBMED");
    }

    private ClinicalTrialsSearchModels.SearchResult clinicalTrials() {
        var trial = new ClinicalTrialsSearchModels.Trial(
                "NCT03594110", "Brief", "Official", "COMPLETED",
                "INTERVENTIONAL", List.of(), List.of(), List.of(),
                "Summary", List.of(), "Sponsor", "2019", "2022", 6609,
                List.of(), true, "REGISTRY_RESULTS_AVAILABLE", true,
                "CLINICAL_TRIALS_GOV", List.of("36331190", "99999999"));
        return new ClinicalTrialsSearchModels.SearchResult(
                "clinicaltrials-search-result/v1", UUID.randomUUID(),
                "CLINICAL_TRIALS_GOV", "TRIAL_REGISTRY", "query",
                "clinicaltrials-query/v1", clock.instant(), 1, 1,
                List.of(trial), "hash", "application/json",
                "clinicaltrials-test/v1", 1, "2026-07-28", false, List.of());
    }
}
