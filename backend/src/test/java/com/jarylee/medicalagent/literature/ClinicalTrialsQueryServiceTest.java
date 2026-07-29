package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ClinicalTrialsQueryServiceTest {
    @Test
    void deterministicallyConvertsConfirmedPecoConceptsWithoutPubMedSyntax() {
        var strategy = new SearchStrategy(
                "search-strategy/v1", "deterministic-peco/v1", "pubmed-query/v1",
                "CONFIRMED", "研究问题", List.of("PUBMED"),
                List.of(
                        new SearchConcept("POPULATION", "人群",
                                List.of("type 2 diabetes[Title/Abstract]"), true),
                        new SearchConcept("EXPOSURE", "暴露",
                                List.of("SGLT2 inhibitor[MeSH Terms]"), true),
                        new SearchConcept("OUTCOME", "结局",
                                List.of("kidney function"), true),
                        new SearchConcept("STUDY_DESIGN", "设计",
                                List.of("cohort studies[MeSH Terms]"), true)),
                "unused", "unused", List.of(), List.of());

        var query = new ClinicalTrialsQueryService().build(strategy);

        assertThat(query.queryVersion()).isEqualTo("clinicaltrials-query/v1");
        assertThat(query.query()).contains(
                "\"type 2 diabetes\"", "\"SGLT2 inhibitor\"", "\"kidney function\"");
        assertThat(query.query()).doesNotContain(
                "Title/Abstract", "MeSH Terms", "cohort studies");
        assertThat(query.concepts()).hasSize(3);
    }
}
