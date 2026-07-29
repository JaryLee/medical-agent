package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.agent.model.ResearchModels.PrototypeResult;
import org.apache.poi.xwpf.usermodel.*;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.*;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class ControlledDocxService {
    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");
    private static final Set<String> ALLOWED = Set.of(
            "${project.title}", "${research.background}", "${research.question}",
            "${research.studyDesign}", "${research.population}", "${research.outcomes}",
            "${research.references}");

    public byte[] render(PrototypeResult result) throws IOException {
        ClassPathResource template = new ClassPathResource("templates/anonymous-research-protocol.docx");
        try (InputStream input = template.getInputStream();
             XWPFDocument document = new XWPFDocument(input);
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            validateTemplate(document);
            Map<String, String> values = values(result);
            for (XWPFParagraph paragraph : document.getParagraphs()) replace(paragraph, values);
            for (XWPFTable table : document.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    for (XWPFTableCell cell : row.getTableCells()) {
                        for (XWPFParagraph paragraph : cell.getParagraphs()) replace(paragraph, values);
                    }
                }
            }
            document.getProperties().getCoreProperties().setCreator("Medical Agent Stage-0 Prototype");
            document.write(output);
            return output.toByteArray();
        }
    }

    private Map<String, String> values(PrototypeResult result) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("${project.title}", result.selectedDirection().title());
        values.put("${research.background}", result.background());
        values.put("${research.question}", result.peco().researchQuestion());
        values.put("${research.studyDesign}", result.selectedDirection().recommendedStudyType().name());
        values.put("${research.population}", result.peco().population());
        values.put("${research.outcomes}", result.peco().outcome());
        values.put("${research.references}", result.literature().stream()
                .map(item -> "[" + item.citationId() + "] " + item.title() + ". " + item.journal()
                        + ". PMID:" + item.pmid() + "; DOI:" + item.doi() + ".")
                .reduce((left, right) -> left + "\n" + right).orElse("证据不足"));
        return values;
    }

    private void validateTemplate(XWPFDocument document) {
        StringBuilder text = new StringBuilder();
        document.getParagraphs().forEach(p -> text.append(p.getText()));
        document.getTables().forEach(t -> t.getRows().forEach(r ->
                r.getTableCells().forEach(c -> text.append(c.getText()))));
        Matcher matcher = PLACEHOLDER.matcher(text);
        while (matcher.find()) {
            if (!ALLOWED.contains(matcher.group())) {
                throw new IllegalArgumentException("模板包含未知占位符: " + matcher.group());
            }
        }
    }

    private void replace(XWPFParagraph paragraph, Map<String, String> values) {
        String original = paragraph.getText();
        if (original == null || values.keySet().stream().noneMatch(original::contains)) return;
        String replaced = original;
        for (Map.Entry<String, String> entry : values.entrySet()) {
            replaced = replaced.replace(entry.getKey(), entry.getValue());
        }
        for (int i = paragraph.getRuns().size() - 1; i >= 0; i--) paragraph.removeRun(i);
        XWPFRun run = paragraph.createRun();
        String[] lines = replaced.split("\\R", -1);
        for (int i = 0; i < lines.length; i++) {
            if (i > 0) run.addBreak();
            run.setText(lines[i]);
        }
        run.setFontFamily("Microsoft YaHei");
        run.setFontSize(10);
    }
}
