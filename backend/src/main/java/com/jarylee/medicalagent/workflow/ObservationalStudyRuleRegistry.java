package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class ObservationalStudyRuleRegistry {
    public static final String CURRENT_VERSION = "observational-study-rules/v1";
    private static final Map<String, String> RESOURCES = Map.of(
            CURRENT_VERSION, "rules/observational-study-rules-v1.json");
    private static final Set<String> ALLOWED_FIELDS = Set.of(
            "population", "exposure", "comparator", "outcome", "setting", "timeFrame");

    private final ObjectMapper json;
    private final Map<String, RuleSet> cache = new ConcurrentHashMap<>();

    public ObservationalStudyRuleRegistry(ObjectMapper json) {
        this.json = json;
    }

    public RuleSet current() {
        return require(CURRENT_VERSION);
    }

    public RuleSet require(String version) {
        String resource = RESOURCES.get(version);
        if (resource == null) throw new IllegalArgumentException("未注册的研究规则版本: " + version);
        return cache.computeIfAbsent(version, ignored -> loadResource(version, resource));
    }

    RuleSet parseForTest(String expectedVersion, String content) {
        return parseAndValidate(expectedVersion, content);
    }

    private RuleSet loadResource(String expectedVersion, String resource) {
        try (var input = new ClassPathResource(resource).getInputStream()) {
            return parseAndValidate(
                    expectedVersion, new String(input.readAllBytes(), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            if (exception instanceof IllegalStateException state) throw state;
            throw new IllegalStateException("无法加载研究规则: " + resource, exception);
        }
    }

    private RuleSet parseAndValidate(String expectedVersion, String content) {
        try {
            RuleSet parsed = json.readValue(content, RuleSet.class);
            return validate(expectedVersion, parsed);
        } catch (IllegalStateException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("研究规则 JSON 无法解析", exception);
        }
    }

    private RuleSet validate(String expectedVersion, RuleSet rules) {
        if (rules == null || !expectedVersion.equals(rules.schemaVersion())) {
            throw new IllegalStateException("研究规则版本不匹配: " + expectedVersion);
        }
        if (rules.unresolvedValueMarkers() == null || rules.unresolvedValueMarkers().isEmpty()
                || rules.unresolvedValueMarkers().stream().anyMatch(this::blank)) {
            throw new IllegalStateException("研究规则缺少未解析值标记");
        }
        if (rules.fields() == null || !rules.fields().keySet().equals(ALLOWED_FIELDS)) {
            throw new IllegalStateException("研究规则字段定义必须与允许字段完全一致");
        }
        Map<String, FieldRule> fields = new LinkedHashMap<>();
        rules.fields().forEach((name, rule) -> {
            if (rule == null || rule.answerQuestionMarkers() == null
                    || rule.answerQuestionMarkers().stream().anyMatch(this::blank)) {
                throw new IllegalStateException("研究规则字段配置无效: " + name);
            }
            fields.put(name, new FieldRule(List.copyOf(rule.answerQuestionMarkers())));
        });
        if (rules.studyTypes() == null
                || !rules.studyTypes().keySet().equals(EnumSet.allOf(StudyType.class))) {
            throw new IllegalStateException("研究类型规则必须覆盖全部观察性研究类型");
        }
        Map<StudyType, StudyRule> studyTypes = new LinkedHashMap<>();
        rules.studyTypes().forEach((studyType, rule) -> {
            if (rule == null || rule.requiredFields() == null || rule.requiredFields().isEmpty()
                    || blank(rule.explanation())) {
                throw new IllegalStateException("研究类型规则不完整: " + studyType);
            }
            List<String> required = List.copyOf(rule.requiredFields());
            if (Set.copyOf(required).size() != required.size()
                    || !ALLOWED_FIELDS.containsAll(required)) {
                throw new IllegalStateException("研究类型包含重复或未知字段: " + studyType);
            }
            studyTypes.put(studyType, new StudyRule(required, rule.explanation().strip()));
        });
        return new RuleSet(
                rules.schemaVersion(), List.copyOf(rules.unresolvedValueMarkers()),
                Map.copyOf(fields), Map.copyOf(studyTypes));
    }

    private boolean blank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record RuleSet(
            String schemaVersion,
            List<String> unresolvedValueMarkers,
            Map<String, FieldRule> fields,
            Map<StudyType, StudyRule> studyTypes) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record FieldRule(List<String> answerQuestionMarkers) {}

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record StudyRule(List<String> requiredFields, String explanation) {}
}
