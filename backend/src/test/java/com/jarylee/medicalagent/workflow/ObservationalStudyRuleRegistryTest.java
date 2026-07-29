package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ObservationalStudyRuleRegistryTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ObservationalStudyRuleRegistry registry =
            new ObservationalStudyRuleRegistry(json);

    @Test
    void loadsRegisteredV1AndKeepsItsContractStable() {
        var rules = registry.current();

        assertThat(rules.schemaVersion())
                .isEqualTo(ObservationalStudyRuleRegistry.CURRENT_VERSION);
        assertThat(rules.studyTypes()).hasSize(3);
        assertThat(rules.fields()).containsOnlyKeys(
                "population", "exposure", "comparator", "outcome", "setting", "timeFrame");
        assertThat(rules.studyTypes().get(
                com.jarylee.medicalagent.agent.model.ResearchModels.StudyType.COHORT)
                .requiredFields())
                .containsExactly(
                        "population", "exposure", "comparator", "outcome", "setting", "timeFrame");
    }

    @Test
    void acceptsAdditiveMetadataButRejectsBreakingV1Changes() throws Exception {
        ObjectNode additive = source();
        additive.put("futureMetadata", "ignored-by-v1");
        assertThat(registry.parseForTest(
                ObservationalStudyRuleRegistry.CURRENT_VERSION, json.writeValueAsString(additive))
                .schemaVersion()).isEqualTo(ObservationalStudyRuleRegistry.CURRENT_VERSION);

        ObjectNode wrongVersion = source();
        wrongVersion.put("schemaVersion", "observational-study-rules/v2");
        assertInvalid(wrongVersion, "版本不匹配");

        ObjectNode missingStudyType = source();
        ((ObjectNode) missingStudyType.get("studyTypes")).remove("COHORT");
        assertInvalid(missingStudyType, "覆盖全部");

        ObjectNode unknownField = source();
        ((ArrayNode) unknownField.at("/studyTypes/COHORT/requiredFields")).add("unknownField");
        assertInvalid(unknownField, "重复或未知字段");

        ObjectNode duplicateField = source();
        ((ArrayNode) duplicateField.at("/studyTypes/COHORT/requiredFields")).add("population");
        assertInvalid(duplicateField, "重复或未知字段");
    }

    @Test
    void rejectsUnregisteredVersion() {
        assertThatThrownBy(() -> registry.require("observational-study-rules/v2"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未注册");
    }

    private void assertInvalid(ObjectNode rules, String message) throws Exception {
        assertThatThrownBy(() -> registry.parseForTest(
                ObservationalStudyRuleRegistry.CURRENT_VERSION, json.writeValueAsString(rules)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(message);
    }

    private ObjectNode source() throws Exception {
        try (var input = new ClassPathResource(
                "rules/observational-study-rules-v1.json").getInputStream()) {
            return (ObjectNode) json.readTree(new String(
                    input.readAllBytes(), StandardCharsets.UTF_8));
        }
    }
}
