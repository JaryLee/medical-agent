package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class SimilarResearchAnalysisServiceTest {
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T06:00:00Z"), ZoneOffset.UTC);
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();

    @Test
    void analyzesOnlyValidatedArticlesAndKeepsRegistryEvidenceDistinct() {
        var repository = new MemorySimilarResearchAnalysisRepository();
        var service = new SimilarResearchAnalysisService(repository, json, clock);

        var result = service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                peco(), strategy(), pubmed(), clinicalTrials(), validation());

        assertThat(result.schemaVersion())
                .isEqualTo("similar-research-analysis-result/v1");
        assertThat(result.analyzedSourceCount()).isEqualTo(2);
        assertThat(result.excludedCitationCount()).isEqualTo(1);
        assertThat(result.similarResearch())
                .extracting(SimilarResearchAnalysisModels.SimilarResearch::sourceType)
                .containsExactlyInAnyOrder("PUBMED_ARTICLE", "TRIAL_REGISTRY");
        assertThat(result.similarResearch())
                .filteredOn(value -> "PUBMED_ARTICLE".equals(value.sourceType()))
                .singleElement().satisfies(value -> {
                    assertThat(value.verificationStatus()).isEqualTo("VERIFIED");
                    assertThat(value.linkedSourceIdentifiers())
                            .containsExactly("NCT03594110");
                });
        assertThat(result.conclusion())
                .contains("暂未发现高度相似研究")
                .doesNotContain("已证明创新");
        assertThat(result.potentialResearchGaps())
                .extracting(SimilarResearchAnalysisModels.ResearchGap::code)
                .contains("POPULATION_EVIDENCE_GAP");
        assertThat(result.inputSha256()).matches("[0-9a-f]{64}");
        assertThat(repository.all()).singleElement().satisfies(record -> {
            assertThat(record.status()).isEqualTo("COMPLETED");
            assertThat(record.analyzedSourceCount()).isEqualTo(2);
            assertThat(record.resultJson()).contains(
                    "similar-research-analysis-result/v1");
        });
    }

    private PecoDefinition peco() {
        return new PecoDefinition(
                "peco/v1", "2型糖尿病成年患者", "SGLT2抑制剂",
                "未暴露对照", "肾功能变化（eGFR）",
                "SGLT2抑制剂是否影响肾功能变化？", StudyType.COHORT, List.of());
    }

    private SearchStrategy strategy() {
        return new SearchStrategy(
                "search-strategy/v1", "deterministic-peco/v1",
                "pubmed-query/v1", "CONFIRMED", peco().researchQuestion(),
                List.of("PUBMED", "CLINICAL_TRIALS_GOV"),
                List.of(
                        new SearchConcept("POPULATION", "研究人群",
                                List.of("2型糖尿病成年患者"), true),
                        new SearchConcept("EXPOSURE", "暴露",
                                List.of("SGLT2抑制剂"), true),
                        new SearchConcept("OUTCOME", "结局",
                                List.of("肾功能变化"), true),
                        new SearchConcept("STUDY_DESIGN", "研究设计",
                                List.of("cohort studies[MeSH Terms]"), true)),
                "query", "query", List.of(), List.of());
    }

    private PubMedSearchModels.SearchResult pubmed() {
        return new PubMedSearchModels.SearchResult(
                "pubmed-search-result/v1", UUID.randomUUID(), "PUBMED",
                "query", "pubmed-query/v1", clock.instant(), 2, 2,
                List.of(
                        article("36331190", "10.1056/NEJMoa2204233",
                                "Empagliflozin in Patients with Chronic Kidney Disease"),
                        article("20000002", "10.9999/mismatch", "Unrelated title")),
                "hash", "application/json", "ncbi-test/v1", 1, List.of());
    }

    private PubMedSearchModels.Article article(String pmid, String doi, String title) {
        return new PubMedSearchModels.Article(
                pmid, doi, title, List.of("Research Group"), "Journal",
                "2023-01-12", "Kidney outcome report.", "ABSTRACT_ONLY",
                true, "PUBMED");
    }

    private ClinicalTrialsSearchModels.SearchResult clinicalTrials() {
        var trial = new ClinicalTrialsSearchModels.Trial(
                "NCT03594110", "Kidney protection with empagliflozin",
                "EMPA-KIDNEY", "COMPLETED", "INTERVENTIONAL", List.of("PHASE3"),
                List.of("Chronic Kidney Disease"), List.of("DRUG: Empagliflozin"),
                "Kidney trial", List.of("Renal outcome"), "Sponsor",
                "2019", "2022", 6609, List.of("United Kingdom"), true,
                "REGISTRY_RESULTS_AVAILABLE", true, "CLINICAL_TRIALS_GOV",
                List.of("36331190"));
        return new ClinicalTrialsSearchModels.SearchResult(
                "clinicaltrials-search-result/v1", UUID.randomUUID(),
                "CLINICAL_TRIALS_GOV", "TRIAL_REGISTRY", "query",
                "clinicaltrials-query/v1", clock.instant(), 1, 1,
                List.of(trial), "hash", "application/json",
                "clinicaltrials-test/v1", 1, "2026-07-28", false, List.of());
    }

    private LiteratureValidationModels.ValidationResult validation() {
        return new LiteratureValidationModels.ValidationResult(
                "literature-validation-result/v1", UUID.randomUUID(),
                clock.instant(), 2, 1, 0, 1, 0, 0,
                List.of(
                        citation("36331190", "10.1056/NEJMoa2204233", "VERIFIED"),
                        citation("20000002", "10.9999/mismatch", "MISMATCH")),
                List.of(new LiteratureValidationModels.EvidenceLink(
                        "NCT03594110", "36331190",
                        "REGISTRY_REFERENCES_PUBLICATION", "RESOLVED")),
                "hash", "application/json", "crossref-test/v1", 2, 0, List.of());
    }

    private LiteratureValidationModels.CitationValidation citation(
            String pmid, String doi, String status) {
        return new LiteratureValidationModels.CitationValidation(
                pmid, doi, status, "CROSSREF", List.of(), null, status);
    }
}
