package com.jarylee.medicalagent.document;

import org.apache.poi.xwpf.model.XWPFHeaderFooterPolicy;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFHeader;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFRun;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;

final class ResearchDraftDocxMarker {
    static final String DRAFT_LABEL = "科研草案";
    static final String DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批";

    private ResearchDraftDocxMarker() {}

    static byte[] mark(byte[] source) {
        try (var input = new ByteArrayInputStream(source);
             var document = new XWPFDocument(input);
             var output = new ByteArrayOutputStream()) {
            var body = document.getDocument().getBody();
            body.insertNewP(0).addNewR().addNewT()
                    .setStringValue(DISCLAIMER);
            body.insertNewP(0).addNewR().addNewT()
                    .setStringValue(DRAFT_LABEL);

            XWPFHeaderFooterPolicy policy =
                    document.createHeaderFooterPolicy();
            XWPFHeader defaultHeader = policy.getDefaultHeader();
            if (defaultHeader == null) {
                defaultHeader = policy.createHeader(
                        XWPFHeaderFooterPolicy.DEFAULT);
            }
            for (XWPFHeader header : document.getHeaderList()) {
                ensureHeaderMarker(header);
            }

            var core = document.getProperties().getCoreProperties();
            core.setTitle(DRAFT_LABEL);
            core.setSubjectProperty(DISCLAIMER);
            core.setKeywords(DRAFT_LABEL + ";" + DISCLAIMER);
            document.write(output);
            return output.toByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "无法写入科研草案文档标识", exception);
        }
    }

    private static void ensureHeaderMarker(XWPFHeader header) {
        boolean present = header.getParagraphs().stream()
                .map(XWPFParagraph::getText)
                .anyMatch(value -> value != null
                        && value.contains(DISCLAIMER));
        if (present) return;
        XWPFParagraph paragraph = header.createParagraph();
        XWPFRun run = paragraph.createRun();
        run.setBold(true);
        run.setText(DRAFT_LABEL + "｜" + DISCLAIMER);
    }
}
