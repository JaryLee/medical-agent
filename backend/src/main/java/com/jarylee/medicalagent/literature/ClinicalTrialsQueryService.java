package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ClinicalTrialsQueryService {
    public static final String QUERY_VERSION = "clinicaltrials-query/v1";
    private static final int MAX_QUERY_LENGTH = 4000;
    private static final List<String> SEARCHABLE_CONCEPTS =
            List.of("POPULATION", "EXPOSURE", "OUTCOME");

    public ClinicalTrialsQuery build(SearchStrategy strategy) {
        if (strategy == null || !"CONFIRMED".equals(strategy.confirmationStatus())) {
            throw new IllegalArgumentException("只有已确认检索策略可以生成临床试验检索式");
        }
        List<QueryConcept> concepts = strategy.concepts().stream()
                .filter(concept -> SEARCHABLE_CONCEPTS.contains(concept.code()))
                .map(this::convert)
                .filter(concept -> !concept.terms().isEmpty())
                .toList();
        if (concepts.size() < 2) {
            throw new IllegalArgumentException("临床试验检索至少需要两个有效PECO概念");
        }
        String query = concepts.stream()
                .map(concept -> concept.terms().stream()
                        .map(this::quoteIfNeeded)
                        .reduce((left, right) -> left + " OR " + right)
                        .map(value -> "(" + value + ")")
                        .orElseThrow())
                .reduce((left, right) -> left + " AND " + right)
                .orElseThrow();
        validate(query);
        return new ClinicalTrialsQuery(
                QUERY_VERSION, query, concepts,
                List.of(
                        "由已确认PECO概念确定性转换，未调用模型翻译或扩展同义词",
                        "使用ClinicalTrials.gov BasicSearch范围检索题名、疾病、干预等注册字段"
                ));
    }

    private QueryConcept convert(SearchConcept concept) {
        List<String> terms = concept.terms().stream()
                .map(this::stripPubMedSyntax)
                .filter(value -> !value.isBlank())
                .distinct()
                .toList();
        return new QueryConcept(concept.code(), concept.label(), terms);
    }

    private String stripPubMedSyntax(String value) {
        String cleaned = value == null ? "" : value
                .replaceAll("(?i)\\[[^\\]]+]$", "")
                .replace("\\", " ")
                .replace("\"", " ")
                .strip();
        return cleaned.replaceAll("\\s+", " ");
    }

    private String quoteIfNeeded(String value) {
        if (value.matches("[\\p{L}\\p{N}._-]+")) return value;
        return "\"" + value.replace("\"", " ") + "\"";
    }

    private void validate(String value) {
        if (value.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("ClinicalTrials.gov检索式不能超过4000字");
        }
        for (int index = 0; index < value.length(); index++) {
            if (Character.isISOControl(value.charAt(index))) {
                throw new IllegalArgumentException("ClinicalTrials.gov检索式包含非法控制字符");
            }
        }
    }

    public record QueryConcept(String code, String label, List<String> terms) {}

    public record ClinicalTrialsQuery(
            String queryVersion,
            String query,
            List<QueryConcept> concepts,
            List<String> limitations
    ) {}
}
