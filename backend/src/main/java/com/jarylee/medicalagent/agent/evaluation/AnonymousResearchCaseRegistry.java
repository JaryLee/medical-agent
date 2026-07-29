package com.jarylee.medicalagent.agent.evaluation;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;

@Component
public class AnonymousResearchCaseRegistry {
    public static final String VERSION = "anonymous-research-cases/v1";
    public static final String CLASSIFICATION = "SYNTHETIC_ANONYMOUS";
    private static final String RESOURCE = "evaluation/anonymous-research-cases-v1.json";

    private final CaseSet cases;

    public AnonymousResearchCaseRegistry(ObjectMapper json) {
        try (var input = new ClassPathResource(RESOURCE).getInputStream()) {
            CaseSet parsed = json.readValue(
                    new String(input.readAllBytes(), StandardCharsets.UTF_8), CaseSet.class);
            this.cases = validate(parsed);
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("无法加载匿名评测案例", exception);
        }
    }

    public CaseSet current() {
        return cases;
    }

    private CaseSet validate(CaseSet value) {
        if (value == null || !VERSION.equals(value.schemaVersion())
                || !CLASSIFICATION.equals(value.dataClassification())) {
            throw new IllegalStateException("匿名评测案例版本或数据分类不受支持");
        }
        if (value.cases() == null || value.cases().size() < 5) {
            throw new IllegalStateException("匿名开发集至少需要5个案例");
        }
        var ids = new HashSet<String>();
        List<EvaluationCase> normalized = value.cases().stream().map(item -> {
            if (item == null || blank(item.id()) || !ids.add(item.id())
                    || blank(item.idea()) || item.idea().length() > 2000
                    || item.expectedKeywords() == null || item.expectedKeywords().isEmpty()
                    || item.expectedKeywords().stream().anyMatch(this::blank)
                    || item.expectedStudyTypes() == null || item.expectedStudyTypes().isEmpty()
                    || item.minimumClarificationQuestions() < 1) {
                throw new IllegalStateException("匿名评测案例不完整或标识重复");
            }
            if (containsDirectIdentifierLabel(item.idea())) {
                throw new IllegalStateException("匿名评测案例包含直接身份标识字段: " + item.id());
            }
            return new EvaluationCase(
                    item.id(), item.idea(), List.copyOf(item.expectedKeywords()),
                    List.copyOf(item.expectedStudyTypes()), item.minimumClarificationQuestions());
        }).toList();
        return new CaseSet(value.schemaVersion(), value.dataClassification(), normalized);
    }

    private boolean containsDirectIdentifierLabel(String value) {
        return value.contains("身份证") || value.contains("住院号")
                || value.contains("患者姓名") || value.contains("手机号");
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CaseSet(
            String schemaVersion, String dataClassification, List<EvaluationCase> cases) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record EvaluationCase(
            String id,
            String idea,
            List<String> expectedKeywords,
            List<StudyType> expectedStudyTypes,
            int minimumClarificationQuestions) {}
}
