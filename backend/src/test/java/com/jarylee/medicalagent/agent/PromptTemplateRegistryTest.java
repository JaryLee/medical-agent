package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateRegistryTest {
    private final PromptTemplateRegistry prompts = new PromptTemplateRegistry();

    @Test
    void loadsVersionedTemplateAndRendersOnlyRegisteredSteps() {
        var prompt = prompts.require("STEP_01_PARSE_IDEA");

        assertThat(prompt.version()).isEqualTo("research-idea-analysis/v1");
        assertThat(prompts.render("STEP_01_PARSE_IDEA", "匿名研究想法"))
                .contains("匿名研究想法")
                .doesNotContain("${input}");
        assertThatThrownBy(() -> prompts.require("STEP_UNKNOWN"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
