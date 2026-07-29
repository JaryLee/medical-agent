package com.jarylee.medicalagent.safety;

import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
public class ExternalModelInputGuard {
    private static final int MAX_EXTERNAL_INPUT_CHARACTERS = 4_000;

    private final SensitiveContentPolicy sensitiveContent;
    private final PromptInjectionPolicy promptInjection;

    public ExternalModelInputGuard(
            SensitiveContentPolicy sensitiveContent,
            PromptInjectionPolicy promptInjection) {
        this.sensitiveContent = sensitiveContent;
        this.promptInjection = promptInjection;
    }

    public String requireAllowed(String rawContent) {
        String content = rawContent == null ? "" : rawContent.strip();
        if (content.isBlank()) {
            throw new IllegalArgumentException("外部模型输入不能为空");
        }
        List<String> blockedRules = new ArrayList<>();
        if (content.length() > MAX_EXTERNAL_INPUT_CHARACTERS) {
            blockedRules.add("EXTERNAL_INPUT_TOO_LONG");
        }
        var sensitiveAssessment = sensitiveContent.assess(content);
        if (!sensitiveAssessment.canSendToExternalModel()) {
            blockedRules.addAll(sensitiveAssessment.matchedRules());
        }
        var injectionAssessment = promptInjection.assess(content);
        if (injectionAssessment.blocked()) {
            blockedRules.addAll(injectionAssessment.matchedRules());
        }
        if (!blockedRules.isEmpty()) {
            throw new IllegalArgumentException(
                    "外部模型输入被安全策略阻止: " + String.join(",", blockedRules));
        }
        return content;
    }
}
