package com.jarylee.medicalagent.review;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewContentHashTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ignoresObjectOrderWhitespaceAndWorkflowMetadata() {
        String first = """
                {
                  "protocolDraft":{"title":"匿名方案","sections":[{"versionNo":1}]},
                  "expertReview":{"status":"WAITING_EXPERT_REVIEW"}
                }
                """;
        String equivalent = """
                {"documentExport":{"status":"PENDING"},
                 "expertReview":{"status":"APPROVED"},
                 "protocolDraft":{"sections":[{"versionNo":1}],"title":"匿名方案"}}
                """;

        assertThat(ReviewContentHash.sha256(json, first))
                .isEqualTo(ReviewContentHash.sha256(json, equivalent));
    }

    @Test
    void changesWhenReviewableContentOrArrayOrderChanges() {
        String first = """
                {"protocolDraft":{"sections":[{"code":"A"},{"code":"B"}]}}
                """;
        String changedContent = """
                {"protocolDraft":{"sections":[{"code":"A"},{"code":"C"}]}}
                """;
        String changedOrder = """
                {"protocolDraft":{"sections":[{"code":"B"},{"code":"A"}]}}
                """;

        assertThat(ReviewContentHash.sha256(json, first))
                .isNotEqualTo(ReviewContentHash.sha256(json, changedContent))
                .isNotEqualTo(ReviewContentHash.sha256(json, changedOrder));
    }
}
