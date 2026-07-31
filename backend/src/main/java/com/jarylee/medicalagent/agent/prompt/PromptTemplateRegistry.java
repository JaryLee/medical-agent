package com.jarylee.medicalagent.agent.prompt;

import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.Map;

@Component
public class PromptTemplateRegistry {
    private static final Map<String, Definition> DEFINITIONS = Map.ofEntries(
            Map.entry("STEP_01_PARSE_IDEA", new Definition(
                    "research-idea-analysis/v1", "prompts/research-idea-analysis-v1.md")),
            Map.entry("STEP_04_GENERATE_RESEARCH_DIRECTIONS", new Definition(
                    "research-directions/v1", "prompts/research-directions-v1.md")),
            Map.entry("STEP_06_BUILD_RESEARCH_QUESTION", new Definition(
                    "research-question/v1", "prompts/research-question-v1.md")),
            Map.entry("PROTOCOL_SECTION_GENERATION", new Definition(
                    "protocol-section-generation/v1",
                    "prompts/protocol-section-generation-v1.md")),
            Map.entry("PROTOCOL_SECTION_REVIEW", new Definition(
                    "protocol-section-review/v1",
                    "prompts/protocol-section-review-v1.md")),
            Map.entry("OBSERVATIONAL_DESIGN_ADVICE", new Definition(
                    "observational-design-advice/v1",
                    "prompts/observational-design-advice-v1.md"))
    );

    public VersionedPrompt require(String stepCode) {
        Definition definition = DEFINITIONS.get(stepCode);
        if (definition == null) throw new IllegalArgumentException("未注册的 Prompt 步骤: " + stepCode);
        try (var input = new ClassPathResource(definition.resource()).getInputStream()) {
            String template = new String(input.readAllBytes(), StandardCharsets.UTF_8);
            if (template.isBlank() || !template.contains("${input}")) {
                throw new IllegalStateException("Prompt 模板必须包含 ${input}: " + definition.resource());
            }
            return new VersionedPrompt(stepCode, definition.version(), template);
        } catch (Exception exception) {
            throw new IllegalStateException("无法加载 Prompt 模板: " + definition.resource(), exception);
        }
    }

    public String render(String stepCode, String input) {
        VersionedPrompt prompt = require(stepCode);
        return prompt.template().replace("${input}", input);
    }

    private record Definition(String version, String resource) {}

    public record VersionedPrompt(String stepCode, String version, String template) {}
}
