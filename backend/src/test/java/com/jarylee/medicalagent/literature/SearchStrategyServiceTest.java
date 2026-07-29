package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SearchStrategyServiceTest {
    private final SearchStrategyService service = new SearchStrategyService();

    @Test
    void generatesVersionedStructuredPubMedStrategyAndPreservesHumanRevision() {
        var peco = new PecoDefinition(
                "peco/v1", "2型糖尿病成人", "SGLT2抑制剂", "其他降糖药",
                "12个月eGFR变化", "SGLT2抑制剂是否影响12个月eGFR变化？",
                StudyType.COHORT, List.of());

        var generated = service.generate(peco);

        assertThat(generated.schemaVersion()).isEqualTo("search-strategy/v1");
        assertThat(generated.queryVersion()).isEqualTo("pubmed-query/v1");
        assertThat(generated.confirmationStatus()).isEqualTo("PENDING_CONFIRMATION");
        assertThat(generated.databases())
                .containsExactly("PUBMED", "CLINICAL_TRIALS_GOV");
        assertThat(generated.concepts()).extracting(SearchStrategyService.SearchConcept::code)
                .containsExactly("POPULATION", "EXPOSURE", "COMPARATOR", "OUTCOME", "STUDY_DESIGN");
        assertThat(generated.pubmedQuery()).contains(
                "\"2型糖尿病成人\"[Title/Abstract]",
                "cohort studies[MeSH Terms]");

        var confirmed = service.confirm(
                generated, generated.pubmedQuery() + "\nNOT animals[MeSH Terms]");

        assertThat(confirmed.confirmationStatus()).isEqualTo("CONFIRMED");
        assertThat(confirmed.generatedPubmedQuery()).isEqualTo(generated.pubmedQuery());
        assertThat(confirmed.pubmedQuery()).endsWith("NOT animals[MeSH Terms]");
    }

    @Test
    void rejectsBlankOversizedAndControlCharacterQueries() {
        var generated = service.generate(new PecoDefinition(
                "peco/v1", "成人", "药物A", "药物B", "死亡",
                "研究问题", StudyType.CASE_CONTROL, List.of()));

        assertThatThrownBy(() -> service.confirm(generated, " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不能为空");
        assertThatThrownBy(() -> service.confirm(generated, "a".repeat(4001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("4000");
        assertThatThrownBy(() -> service.confirm(generated, "term\u0000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("控制字符");
    }
}
