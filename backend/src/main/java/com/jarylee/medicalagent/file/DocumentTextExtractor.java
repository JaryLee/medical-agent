package com.jarylee.medicalagent.file;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;

@Component
public class DocumentTextExtractor {
    private static final int MAX_PDF_PAGES = 200;
    private static final int MAX_EXTRACTED_CHARACTERS = 200_000;

    public Extraction extract(String extension, byte[] content) {
        try {
            String text = switch (extension) {
                case "pdf" -> extractPdf(content);
                case "docx" -> extractDocx(content);
                default -> "";
            };
            String normalized = text.strip();
            if (normalized.length() > MAX_EXTRACTED_CHARACTERS) {
                normalized = normalized.substring(0, MAX_EXTRACTED_CHARACTERS);
            }
            return new Extraction(normalized, normalized.isEmpty() ? "EMPTY" : "EXTRACTED");
        } catch (Exception exception) {
            throw new IllegalArgumentException("文档文本提取失败", exception);
        }
    }

    private String extractPdf(byte[] content) throws Exception {
        try (var document = Loader.loadPDF(content)) {
            if (document.getNumberOfPages() > MAX_PDF_PAGES) {
                throw new IllegalArgumentException("PDF 页数不能超过 200 页");
            }
            return new PDFTextStripper().getText(document);
        }
    }

    private String extractDocx(byte[] content) throws Exception {
        try (var document = new XWPFDocument(new ByteArrayInputStream(content))) {
            List<String> fragments = new ArrayList<>();
            document.getParagraphs().forEach(paragraph -> fragments.add(paragraph.getText()));
            document.getTables().forEach(table -> table.getRows().forEach(row ->
                    row.getTableCells().forEach(cell -> fragments.add(cell.getText()))));
            return String.join(System.lineSeparator(), fragments);
        }
    }

    public record Extraction(String text, String status) {}
}
