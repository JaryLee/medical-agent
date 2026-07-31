package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProtocolSectionCandidateValidatorTest {
    private final ProtocolSectionCandidateValidator validator =
            new ProtocolSectionCandidateValidator();

    @Test
    void acceptsOnlyDeclaredIdentifiersFromTheAssignedAllowlist() {
        var candidate = candidate(
                "当前摘要级依据为 PMID:12345678 与 DOI:10.1000/test.1，"
                        + "其支持关系仍需专家阅读全文确认。",
                List.of("PMID:12345678", "DOI:10.1000/test.1"));

        var result = validator.validate(
                "BACKGROUND",
                Set.of("PMID:12345678", "DOI:10.1000/test.1"),
                candidate);

        assertThat(result.status()).isEqualTo("PASSED");
        assertThat(result.usedEvidenceIdentifiers())
                .containsExactlyInAnyOrder(
                        "PMID:12345678", "DOI:10.1000/test.1");
    }

    @Test
    void rejectsInventedIdentifierInternalIdAndApprovalClaim() {
        assertThatThrownBy(() -> validator.validate(
                "BACKGROUND",
                Set.of("PMID:12345678"),
                candidate(
                        "当前依据为 PMID:99999999，仍需专家确认。",
                        List.of("PMID:99999999"))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未分配");

        assertThatThrownBy(() -> validator.validate(
                "BACKGROUND",
                Set.of(),
                candidate(
                        "内部 STEP_15 与 123e4567-e89b-42d3-a456-426614174000 不应出现。",
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("内部标识");

        assertThatThrownBy(() -> validator.validate(
                "BACKGROUND",
                Set.of(),
                candidate(
                        "本章节已经证实因果并且无需专家确认，可以直接使用。",
                        List.of())))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("越界结论");
    }

    private ProtocolSectionModel.GenerationCandidate candidate(
            String content,
            List<String> evidence) {
        return new ProtocolSectionModel.GenerationCandidate(
                ProtocolSectionModel.GENERATION_OUTPUT_SCHEMA,
                "BACKGROUND",
                content,
                evidence,
                List.of("由医学专家确认"),
                List.of("仅为摘要级候选依据"));
    }
}
