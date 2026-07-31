package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.ProtocolSectionModel;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class ProtocolSectionCandidateValidator {
    private static final Pattern PMID =
            Pattern.compile("(?i)\\bPMID\\s*[:：]?\\s*(\\d{1,20})\\b");
    private static final Pattern NCT =
            Pattern.compile("(?i)\\b(NCT\\d{8})\\b");
    private static final Pattern DOI =
            Pattern.compile("(?i)\\b(?:DOI\\s*[:：]?\\s*)?(10\\.\\d{4,9}/[-._;()/:A-Z0-9]+)");
    private static final Pattern UUID =
            Pattern.compile("(?i)\\b[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}\\b");
    private static final Pattern INTERNAL_STEP =
            Pattern.compile("\\bSTEP[_-]?\\d{1,3}\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> FORBIDDEN_CLAIMS = List.of(
            "已获伦理批准",
            "伦理审查已通过",
            "正式批准",
            "已经证实因果",
            "确证因果",
            "保证发表",
            "无需专家确认");

    public ValidationReport validate(
            String expectedSectionCode,
            Set<String> allowedEvidenceIdentifiers,
            ProtocolSectionModel.GenerationCandidate candidate) {
        if (candidate == null
                || !ProtocolSectionModel.GENERATION_OUTPUT_SCHEMA.equals(
                candidate.schemaVersion())) {
            throw new IllegalArgumentException("模型章节候选 Schema 不受支持");
        }
        if (!expectedSectionCode.equals(candidate.sectionCode())) {
            throw new IllegalArgumentException("模型章节候选与目标章节不一致");
        }
        String content = normalizeContent(candidate.contentMarkdown());
        if (UUID.matcher(content).find() || INTERNAL_STEP.matcher(content).find()) {
            throw new IllegalArgumentException("模型章节候选包含内部标识");
        }
        for (String forbidden : FORBIDDEN_CLAIMS) {
            if (content.contains(forbidden)) {
                throw new IllegalArgumentException("模型章节候选包含越界结论: " + forbidden);
            }
        }

        Set<String> allowed = normalizeAll(allowedEvidenceIdentifiers);
        Set<String> declared = normalizeAll(candidate.usedEvidenceIdentifiers());
        if (!allowed.containsAll(declared)) {
            throw new IllegalArgumentException("模型声明了未分配的证据标识符");
        }
        Set<String> detected = detectIdentifiers(content);
        if (!allowed.containsAll(detected)) {
            throw new IllegalArgumentException("模型生成了未经工具核验的标识符");
        }
        if (!declared.containsAll(detected) || !detected.containsAll(declared)) {
            throw new IllegalArgumentException("模型正文与证据标识符声明不一致");
        }
        List<String> issues = normalizeList(
                candidate.issuesToConfirm(), 20, 1000, "待确认事项");
        List<String> limitations = normalizeList(
                candidate.limitations(), 20, 1000, "限制");
        if (limitations.isEmpty()) {
            throw new IllegalArgumentException("模型章节候选必须声明限制");
        }
        return new ValidationReport(
                content, List.copyOf(declared), issues, limitations,
                "PASSED", List.of(
                        "SECTION_SCHEMA_VALID",
                        "IDENTIFIER_ALLOWLIST_VALID",
                        "NO_INTERNAL_IDENTIFIER",
                        "NO_FORBIDDEN_APPROVAL_CLAIM"));
    }

    public Set<String> normalizedAllowed(List<String> sourceIdentifiers) {
        return normalizeAll(sourceIdentifiers);
    }

    private String normalizeContent(String value) {
        if (value == null || value.strip().length() < 20) {
            throw new IllegalArgumentException("模型章节候选内容过短");
        }
        String normalized = value.strip();
        if (normalized.length() > 30000) {
            throw new IllegalArgumentException("模型章节候选内容不能超过 30000 字");
        }
        if (normalized.chars().anyMatch(character ->
                Character.isISOControl(character)
                        && character != '\n' && character != '\r' && character != '\t')) {
            throw new IllegalArgumentException("模型章节候选包含不支持的控制字符");
        }
        return normalized;
    }

    private Set<String> detectIdentifiers(String content) {
        LinkedHashSet<String> result = new LinkedHashSet<>();
        Matcher pmid = PMID.matcher(content);
        while (pmid.find()) result.add("PMID:" + pmid.group(1));
        Matcher nct = NCT.matcher(content);
        while (nct.find()) result.add(nct.group(1).toUpperCase(Locale.ROOT));
        Matcher doi = DOI.matcher(content);
        while (doi.find()) {
            result.add("DOI:" + trimDoi(doi.group(1)).toLowerCase(Locale.ROOT));
        }
        return Set.copyOf(result);
    }

    private Set<String> normalizeAll(Iterable<String> values) {
        if (values == null) return Set.of();
        LinkedHashSet<String> normalized = new LinkedHashSet<>();
        for (String value : values) {
            String identifier = normalizeIdentifier(value);
            if (identifier != null) normalized.add(identifier);
        }
        return Set.copyOf(normalized);
    }

    private String normalizeIdentifier(String value) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        Matcher pmid = PMID.matcher(normalized);
        if (pmid.matches()) return "PMID:" + pmid.group(1);
        Matcher nct = NCT.matcher(normalized);
        if (nct.matches()) return nct.group(1).toUpperCase(Locale.ROOT);
        Matcher doi = DOI.matcher(normalized);
        if (doi.matches()) {
            return "DOI:" + trimDoi(doi.group(1)).toLowerCase(Locale.ROOT);
        }
        return null;
    }

    private String trimDoi(String value) {
        return value.replaceFirst("[.,;。，；)）]+$", "");
    }

    private List<String> normalizeList(
            List<String> values, int maxItems, int maxLength, String label) {
        if (values == null) return List.of();
        if (values.size() > maxItems) {
            throw new IllegalArgumentException(label + "数量过多");
        }
        List<String> normalized = new ArrayList<>();
        for (String value : values) {
            if (value == null || value.isBlank() || value.strip().length() > maxLength) {
                throw new IllegalArgumentException(label + "格式不合法");
            }
            normalized.add(value.strip());
        }
        return List.copyOf(normalized);
    }

    public record ValidationReport(
            String content,
            List<String> usedEvidenceIdentifiers,
            List<String> issuesToConfirm,
            List<String> limitations,
            String status,
            List<String> checks) {}
}
