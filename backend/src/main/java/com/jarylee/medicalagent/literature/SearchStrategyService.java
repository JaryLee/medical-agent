package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.agent.model.ResearchModels.PecoDefinition;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class SearchStrategyService {
    public static final String SCHEMA_VERSION = "search-strategy/v1";
    public static final String GENERATOR_VERSION = "deterministic-peco/v1";
    public static final String QUERY_VERSION = "pubmed-query/v1";
    private static final int MAX_QUERY_LENGTH = 4000;

    public SearchStrategy generate(PecoDefinition peco) {
        List<SearchConcept> concepts = new ArrayList<>();
        addConcept(concepts, "POPULATION", "研究人群", peco.population(), true);
        addConcept(concepts, "EXPOSURE", "暴露/干预", peco.exposure(), true);
        addConcept(concepts, "COMPARATOR", "对照", peco.comparator(), false);
        addConcept(concepts, "OUTCOME", "结局", peco.outcome(), true);
        concepts.add(studyDesignConcept(peco.studyType()));

        String query = concepts.stream()
                .filter(SearchConcept::required)
                .map(this::conceptExpression)
                .reduce((left, right) -> left + "\nAND " + right)
                .orElseThrow(() -> new IllegalArgumentException("PECO缺少可生成检索式的核心概念"));

        return new SearchStrategy(
                SCHEMA_VERSION,
                GENERATOR_VERSION,
                QUERY_VERSION,
                "PENDING_CONFIRMATION",
                peco.researchQuestion(),
                List.of("PUBMED", "CLINICAL_TRIALS_GOV"),
                List.copyOf(concepts),
                query,
                query,
                List.of("不自动限定语言、发表时间、年龄或全文可用性"),
                List.of(
                        "当前策略覆盖 PubMed 和 ClinicalTrials.gov，未覆盖 CNKI、万方、维普及灰色文献",
                        "结构化概念由 PECO 自动转换，执行检索前必须由医生或信息专家复核",
                        "人工确认的是 PubMed 检索式；ClinicalTrials.gov 查询由同一组确认概念确定性转换",
                        "检索结果只能辅助判断研究现状，不能用于声称已证明创新"
                )
        );
    }

    public SearchStrategy confirm(SearchStrategy generated, String submittedQuery) {
        if (generated == null || !SCHEMA_VERSION.equals(generated.schemaVersion())) {
            throw new IllegalArgumentException("检索策略版本不受支持");
        }
        String normalized = normalizeQuery(submittedQuery);
        return new SearchStrategy(
                generated.schemaVersion(),
                generated.generatorVersion(),
                generated.queryVersion(),
                "CONFIRMED",
                generated.originalResearchQuestion(),
                generated.databases(),
                generated.concepts(),
                generated.generatedPubmedQuery(),
                normalized,
                generated.filters(),
                generated.limitations()
        );
    }

    private void addConcept(List<SearchConcept> concepts, String code, String label,
                            String value, boolean required) {
        if (value != null && !value.isBlank()) {
            concepts.add(new SearchConcept(code, label, List.of(value.strip()), required));
        } else if (required) {
            throw new IllegalArgumentException("PECO缺少" + label);
        }
    }

    private SearchConcept studyDesignConcept(StudyType studyType) {
        List<String> terms = switch (studyType) {
            case CROSS_SECTIONAL -> List.of(
                    "cross-sectional studies[MeSH Terms]",
                    "cross-sectional study[Title/Abstract]");
            case COHORT -> List.of(
                    "cohort studies[MeSH Terms]",
                    "cohort study[Title/Abstract]",
                    "longitudinal study[Title/Abstract]");
            case CASE_CONTROL -> List.of(
                    "case-control studies[MeSH Terms]",
                    "case-control study[Title/Abstract]");
        };
        return new SearchConcept("STUDY_DESIGN", "研究设计", terms, true);
    }

    private String conceptExpression(SearchConcept concept) {
        return concept.terms().stream()
                .map(term -> term.contains("[") ? term : quote(term) + "[Title/Abstract]")
                .reduce((left, right) -> left + " OR " + right)
                .map(expression -> "(" + expression + ")")
                .orElseThrow();
    }

    private String quote(String value) {
        String safe = value.replace("\\", " ").replace("\"", " ").trim();
        return "\"" + safe + "\"";
    }

    private String normalizeQuery(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("PubMed检索式不能为空");
        }
        String normalized = value.strip();
        if (normalized.length() > MAX_QUERY_LENGTH) {
            throw new IllegalArgumentException("PubMed检索式不能超过4000字");
        }
        for (int index = 0; index < normalized.length(); index++) {
            char character = normalized.charAt(index);
            if (Character.isISOControl(character)
                    && character != '\n' && character != '\r' && character != '\t') {
                throw new IllegalArgumentException("PubMed检索式包含非法控制字符");
            }
        }
        return normalized;
    }

    public record SearchConcept(
            String code,
            String label,
            List<String> terms,
            boolean required
    ) {}

    public record SearchStrategy(
            String schemaVersion,
            String generatorVersion,
            String queryVersion,
            String confirmationStatus,
            String originalResearchQuestion,
            List<String> databases,
            List<SearchConcept> concepts,
            String generatedPubmedQuery,
            String pubmedQuery,
            List<String> filters,
            List<String> limitations
    ) {}
}
