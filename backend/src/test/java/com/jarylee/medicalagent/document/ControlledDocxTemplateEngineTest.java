package com.jarylee.medicalagent.document;

import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Font;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControlledDocxTemplateEngineTest {
    private final ControlledDocxTemplateEngine engine =
            new ControlledDocxTemplateEngine();

    @Test
    void validatesAndRendersParagraphTableHeaderAndFooterPlaceholders()
            throws Exception {
        byte[] template;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("${project.title}");
            document.createTable(1, 1).getRow(0).getCell(0)
                    .setText("${research.background}");
            document.createHeader(
                            org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                    .createParagraph().createRun().setText("${project.department}");
            document.createFooter(
                            org.apache.poi.wp.usermodel.HeaderFooterType.DEFAULT)
                    .createParagraph().createRun().setText("${research.references}");
            document.write(output);
            template = output.toByteArray();
        }

        var validation = engine.validate(template);
        assertThat(validation.placeholders()).containsExactlyInAnyOrder(
                "${project.title}", "${research.background}",
                "${project.department}", "${research.references}");

        byte[] rendered = engine.render(template, Map.of(
                "${project.title}", "匿名队列研究方案",
                "${research.background}", "研究背景",
                "${project.department}", "心内科",
                "${research.references}", "[1] PMID:123"));
        try (var document =
                     new XWPFDocument(new ByteArrayInputStream(rendered))) {
            assertThat(document.getParagraphs().getFirst().getText())
                    .isEqualTo("匿名队列研究方案");
            assertThat(document.getTables().getFirst().getText())
                    .contains("研究背景");
            assertThat(document.getHeaderList().getFirst().getText())
                    .contains("心内科");
            assertThat(document.getFooterList().getFirst().getText())
                    .contains("PMID:123");
        }
    }

    @Test
    void rejectsUnknownPlaceholderBeforePublishing() throws Exception {
        byte[] template;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("${project.title}");
            document.createParagraph().createRun().setText("${research.unknown}");
            document.write(output);
            template = output.toByteArray();
        }
        assertThatThrownBy(() -> engine.validate(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("未知占位符");
    }

    @Test
    void validatesAndRendersListsReferenceRowsAndSafeLogo() throws Exception {
        byte[] template;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("${project.logo}");
            document.createParagraph().createRun().setText("${project.title}");
            document.createParagraph().createRun()
                    .setText("${list.research.objectives}");
            var table = document.createTable(2, 2);
            table.getRow(0).getCell(0).setText("No.");
            table.getRow(0).getCell(1).setText("Reference");
            table.getRow(1).getCell(0)
                    .setText("${repeat.ref.index}");
            table.getRow(1).getCell(1)
                    .setText("${repeat.ref.text}");
            document.write(output);
            template = output.toByteArray();
        }

        assertThat(engine.validate(template).placeholders())
                .contains(
                        "${project.logo}",
                        "${project.title}",
                        "${list.research.objectives}",
                        "${repeat.ref.index}",
                        "${repeat.ref.text}");

        Map<String, String> values = Map.of(
                "${project.title}", "Structured protocol",
                "${research.objectives}", "First objective\nSecond objective",
                "${research.references}", "First citation\nSecond citation");
        var data = ControlledDocxTemplateEngine.RenderData.fromTextValues(
                values,
                new ControlledDocxTemplateEngine.LogoImage(
                        testLogo(), "synthetic-logo.png"));
        byte[] rendered = engine.render(template, data);

        try (var document =
                     new XWPFDocument(new ByteArrayInputStream(rendered))) {
            assertThat(document.getParagraphs())
                    .extracting(value -> value.getText())
                    .contains("Structured protocol",
                            "First objective", "Second objective")
                    .doesNotContain("${project.logo}");
            assertThat(document.getParagraphs().stream()
                    .filter(value -> value.getText().endsWith("objective"))
                    .map(value -> value.getNumID())
                    .toList())
                    .allMatch(value -> value != null);
            assertThat(document.getTables().getFirst().getRows()).hasSize(3);
            assertThat(document.getTables().getFirst().getRow(1)
                    .getTableCells().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + " " + right))
                    .contains("1", "First citation");
            assertThat(document.getTables().getFirst().getRow(2)
                    .getTableCells().stream()
                    .map(value -> value.getText())
                    .reduce("", (left, right) -> left + " " + right))
                    .contains("2", "Second citation");
            assertThat(document.getAllPictures()).hasSize(1);
        }
    }

    @Test
    void rejectsStructuralDirectiveThatDoesNotOwnItsParagraph()
            throws Exception {
        byte[] template;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("${project.title}");
            document.createParagraph().createRun()
                    .setText("Objectives: ${list.research.objectives}");
            document.write(output);
            template = output.toByteArray();
        }

        assertThatThrownBy(() -> engine.validate(template))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("必须独占一个段落");
    }

    @Test
    void rejectsNonPngOrJpegLogo() throws Exception {
        byte[] template;
        try (var document = new XWPFDocument();
             var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText("${project.logo}");
            document.createParagraph().createRun().setText("${project.title}");
            document.createParagraph().createRun()
                    .setText("${research.background}");
            document.write(output);
            template = output.toByteArray();
        }
        var data = ControlledDocxTemplateEngine.RenderData.fromTextValues(
                Map.of(
                        "${project.title}", "Protocol",
                        "${research.background}", "Background"),
                new ControlledDocxTemplateEngine.LogoImage(
                        "not-an-image".getBytes(), "logo.svg"));

        assertThatThrownBy(() -> engine.render(template, data))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("仅允许 PNG 或 JPEG");
    }

    @Test
    void validatesAndRendersTwoClearlySyntheticHospitalTemplates()
            throws Exception {
        Map<String, String> values = Map.ofEntries(
                Map.entry("${project.title}", "匿名队列研究方案"),
                Map.entry("${project.principalInvestigator}", "匿名负责人"),
                Map.entry("${project.department}", "匿名科室"),
                Map.entry("${research.background}", "研究背景示例内容。"),
                Map.entry("${research.question}", "暴露与结局是否相关？"),
                Map.entry("${research.objectives}",
                        "验证结构化列表。\n验证真实 Word 项目符号。"),
                Map.entry("${research.studyDesign}", "队列研究"),
                Map.entry("${research.population}", "匿名研究对象"),
                Map.entry("${research.inclusionCriteria}", "纳入标准示例。"),
                Map.entry("${research.exclusionCriteria}", "排除标准示例。"),
                Map.entry("${research.outcomes}", "主要终点示例。"),
                Map.entry("${research.variables}", "变量与混杂因素示例。"),
                Map.entry("${research.statisticalPlan}", "统计分析计划示例。"),
                Map.entry("${research.ethicalConsiderations}",
                        "伦理与数据安全说明。"),
                Map.entry("${research.references}",
                        "[1] Anonymous Author. Verified reference[J]. "
                                + "PMID:123; DOI:10.1000/demo. [摘要级证据]\n"
                                + "[2] Anonymous Team. Public source[J]. "
                                + "PMID:456; DOI:10.1000/demo2. [全文证据]"));
        String qaDirectory = System.getProperty("syntheticTemplateQaDir", "");

        for (String name : List.of(
                "synthetic-hospital-a-protocol.docx",
                "synthetic-hospital-b-protocol.docx")) {
            byte[] template;
            try (var input = new ClassPathResource(
                    "templates/" + name).getInputStream()) {
                template = input.readAllBytes();
            }
            Set<String> expected = new LinkedHashSet<>(
                    ControlledDocxTemplateEngine.ALLOWED_PLACEHOLDERS);
            if (name.contains("-b-")) {
                expected.remove("${research.objectives}");
                expected.remove("${research.references}");
                expected.add("${project.logo}");
                expected.add("${list.research.objectives}");
                expected.add("${repeat.ref.index}");
                expected.add("${repeat.ref.text}");
            }
            assertThat(engine.validate(template).placeholders())
                    .containsExactlyInAnyOrderElementsOf(
                            expected);
            Map<String, String> templateValues = values;
            if (name.contains("-a-")) {
                templateValues = new java.util.LinkedHashMap<>(values);
                templateValues.put(
                        "${research.objectives}", "验证研究目标。");
                templateValues.put(
                        "${research.references}",
                        "[1] Anonymous Author. Verified reference[J]. "
                                + "PMID:123; DOI:10.1000/demo. [摘要级证据]");
            }
            byte[] rendered = engine.render(
                    template,
                    ControlledDocxTemplateEngine.RenderData.fromTextValues(
                            templateValues,
                            new ControlledDocxTemplateEngine.LogoImage(
                                    testLogo(), "synthetic-logo.png")));
            if (!qaDirectory.isBlank()) {
                Path output = Path.of(qaDirectory).toAbsolutePath()
                        .resolve(name);
                Files.createDirectories(output.getParent());
                Files.write(output, rendered);
            }
            try (var document =
                         new XWPFDocument(new ByteArrayInputStream(rendered))) {
                String text = document.getParagraphs().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right)
                        + document.getTables().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right)
                        + document.getHeaderList().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right)
                        + document.getFooterList().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right);
                assertThat(text)
                        .contains("匿名队列研究方案")
                        .contains("PMID:123")
                        .contains("非真实医院材料")
                        .doesNotContain("${");
                if (name.contains("-b-")) {
                    assertThat(text).contains("PMID:456");
                    assertThat(document.getAllPictures()).hasSize(1);
                    assertThat(document.getTables().getLast().getRows())
                            .hasSize(3);
                }
            }
        }
    }

    private byte[] testLogo() throws Exception {
        BufferedImage image = new BufferedImage(
                160, 40, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        graphics.setColor(new Color(46, 116, 181));
        graphics.fillRect(0, 0, image.getWidth(), image.getHeight());
        graphics.setColor(Color.WHITE);
        graphics.setFont(new Font("Arial", Font.BOLD, 16));
        graphics.drawString("SYNTHETIC", 30, 26);
        graphics.dispose();
        try (var output = new ByteArrayOutputStream()) {
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        }
    }
}
