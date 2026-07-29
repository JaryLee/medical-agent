package com.jarylee.medicalagent.file;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentTextExtractorTest {
    private final DocumentTextExtractor extractor = new DocumentTextExtractor();

    @Test
    void extractsPdfText() throws Exception {
        byte[] content;
        try (var document = new PDDocument(); var output = new ByteArrayOutputStream()) {
            var page = new PDPage();
            document.addPage(page);
            try (var stream = new PDPageContentStream(document, page)) {
                stream.beginText();
                stream.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                stream.newLineAtOffset(72, 720);
                stream.showText("anonymous cohort study");
                stream.endText();
            }
            document.save(output);
            content = output.toByteArray();
        }

        var result = extractor.extract("pdf", content);

        assertThat(result.status()).isEqualTo("EXTRACTED");
        assertThat(result.text()).contains("anonymous cohort study");
    }

    @Test
    void extractsDocxParagraphAndTableText() throws Exception {
        byte[] content;
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("study protocol");
            document.createTable(1, 1).getRow(0).getCell(0).setText("anonymous participants");
            document.write(output);
            content = output.toByteArray();
        }

        var result = extractor.extract("docx", content);

        assertThat(result.status()).isEqualTo("EXTRACTED");
        assertThat(result.text()).contains("study protocol", "anonymous participants");
    }
}
