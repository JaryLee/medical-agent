package com.jarylee.medicalagent.document;

import org.apache.poi.util.Units;
import org.apache.poi.xwpf.usermodel.*;
import org.apache.xmlbeans.XmlCursor;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTAbstractNum;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTLvl;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTPPr;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.CTRow;
import org.openxmlformats.schemas.wordprocessingml.x2006.main.STNumberFormat;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
public class ControlledDocxTemplateEngine {
    public static final String PLACEHOLDER_SCHEMA_VERSION =
            "controlled-docx-placeholders/v2";
    public static final Set<String> ALLOWED_PLACEHOLDERS = Set.of(
            "${project.title}",
            "${project.principalInvestigator}",
            "${project.department}",
            "${research.background}",
            "${research.question}",
            "${research.objectives}",
            "${research.studyDesign}",
            "${research.population}",
            "${research.inclusionCriteria}",
            "${research.exclusionCriteria}",
            "${research.outcomes}",
            "${research.variables}",
            "${research.statisticalPlan}",
            "${research.ethicalConsiderations}",
            "${research.references}");
    public static final String LOGO_DIRECTIVE = "${project.logo}";
    public static final String REFERENCE_INDEX_DIRECTIVE =
            "${repeat.ref.index}";
    public static final String REFERENCE_TEXT_DIRECTIVE =
            "${repeat.ref.text}";
    public static final Map<String, String> LIST_DIRECTIVES = Map.of(
            "${list.research.objectives}", "${research.objectives}",
            "${list.research.inclusionCriteria}", "${research.inclusionCriteria}",
            "${list.research.exclusionCriteria}", "${research.exclusionCriteria}",
            "${list.research.outcomes}", "${research.outcomes}",
            "${list.research.variables}", "${research.variables}");
    public static final Set<String> ALLOWED_DIRECTIVES = Set.of(
            LOGO_DIRECTIVE,
            "${list.research.objectives}",
            "${list.research.inclusionCriteria}",
            "${list.research.exclusionCriteria}",
            "${list.research.outcomes}",
            "${list.research.variables}",
            REFERENCE_INDEX_DIRECTIVE,
            REFERENCE_TEXT_DIRECTIVE);

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");
    private static final Pattern LIST_PREFIX = Pattern.compile(
            "^\\s*(?:[-*•·]|\\[\\d+]|\\d+[.)、]|"
                    + "[（(]?[一二三四五六七八九十]+[)）、])\\s*");
    private static final int MAX_TEMPLATE_BYTES = 10 * 1024 * 1024;
    private static final int MAX_LOGO_BYTES = 1024 * 1024;
    private static final int MAX_LOGO_PIXELS = 2_000;
    private static final int MAX_LOGO_WIDTH = 480;
    private static final int MAX_LOGO_HEIGHT = 160;

    public ValidationResult validate(byte[] content) {
        if (content == null || content.length == 0) {
            throw new IllegalArgumentException("模板文件不能为空");
        }
        if (content.length > MAX_TEMPLATE_BYTES) {
            throw new IllegalArgumentException("DOCX 模板不能超过 10MB");
        }
        inspectPackage(content);
        try (XWPFDocument document =
                     new XWPFDocument(new ByteArrayInputStream(content))) {
            Set<String> placeholders = extractPlaceholders(document);
            List<String> unknown = placeholders.stream()
                    .filter(value -> !ALLOWED_PLACEHOLDERS.contains(value)
                            && !ALLOWED_DIRECTIVES.contains(value))
                    .sorted()
                    .toList();
            if (!unknown.isEmpty()) {
                throw new IllegalArgumentException(
                        "模板包含未知占位符：" + String.join("、", unknown));
            }
            validateDirectivePlacement(document);
            if (!placeholders.contains("${project.title}")) {
                throw new IllegalArgumentException(
                        "模板必须包含 ${project.title}");
            }
            if (placeholders.stream().noneMatch(
                    value -> value.contains("research."))) {
                throw new IllegalArgumentException(
                        "模板至少需要一个 research.* 占位符或结构化指令");
            }
            return new ValidationResult(
                    List.copyOf(placeholders), "模板结构和受控占位协议校验通过");
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取 DOCX 模板", exception);
        }
    }

    public byte[] render(byte[] template, Map<String, String> values) {
        return render(template, RenderData.fromTextValues(values));
    }

    public byte[] render(byte[] template, RenderData data) {
        ValidationResult validation = validate(template);
        try (XWPFDocument document =
                     new XWPFDocument(new ByteArrayInputStream(template));
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            replaceAll(document, data == null ? RenderData.empty() : data);
            Set<String> unresolved = extractPlaceholders(document);
            if (!unresolved.isEmpty()) {
                throw new IllegalArgumentException(
                        "导出前仍有未解析占位符：" + String.join("、", unresolved));
            }
            document.getProperties().getCoreProperties()
                    .setCreator("Medical Research Agent");
            document.getProperties().getCoreProperties()
                    .setDescription("受控模板导出；占位符协议 "
                            + PLACEHOLDER_SCHEMA_VERSION + "；字段数 "
                            + validation.placeholders().size());
            document.write(output);
            return output.toByteArray();
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("DOCX 受控模板渲染失败", exception);
        }
    }

    private void inspectPackage(byte[] content) {
        boolean contentTypes = false;
        boolean mainDocument = false;
        try (ZipInputStream zip =
                     new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String name = entry.getName().replace('\\', '/').toLowerCase();
                if ("[content_types].xml".equals(name)) contentTypes = true;
                if ("word/document.xml".equals(name)) mainDocument = true;
                if (name.endsWith("vbaproject.bin")
                        || name.startsWith("word/activex/")
                        || name.startsWith("word/embeddings/")) {
                    throw new IllegalArgumentException(
                            "模板不能包含宏、ActiveX 或嵌入对象");
                }
                if (name.endsWith(".rels")) {
                    String relationships =
                            new String(zip.readAllBytes(), StandardCharsets.UTF_8);
                    if (relationships.matches(
                            "(?is).*TargetMode\\s*=\\s*[\"']External[\"'].*")) {
                        throw new IllegalArgumentException(
                                "模板不能包含外部关系或远程资源");
                    }
                }
            }
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("DOCX 压缩包结构无效", exception);
        }
        if (!contentTypes || !mainDocument) {
            throw new IllegalArgumentException("文件不是有效的 DOCX 文档包");
        }
    }

    private Set<String> extractPlaceholders(XWPFDocument document) {
        Set<String> values = new LinkedHashSet<>();
        collectParagraphs(document.getParagraphs(), values);
        collectTables(document.getTables(), values);
        for (XWPFHeader header : document.getHeaderList()) {
            collectParagraphs(header.getParagraphs(), values);
            collectTables(header.getTables(), values);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            collectParagraphs(footer.getParagraphs(), values);
            collectTables(footer.getTables(), values);
        }
        return values;
    }

    private void collectParagraphs(
            List<XWPFParagraph> paragraphs, Set<String> values) {
        for (XWPFParagraph paragraph : paragraphs) {
            Matcher matcher = PLACEHOLDER.matcher(paragraph.getText());
            while (matcher.find()) values.add(matcher.group());
        }
    }

    private void collectTables(List<XWPFTable> tables, Set<String> values) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                for (XWPFTableCell cell : row.getTableCells()) {
                    collectParagraphs(cell.getParagraphs(), values);
                    collectTables(cell.getTables(), values);
                }
            }
        }
    }

    private void validateDirectivePlacement(XWPFDocument document) {
        validateStandaloneParagraphs(document.getParagraphs(), false);
        validateDirectiveTables(document.getTables());
        for (XWPFHeader header : document.getHeaderList()) {
            validateStandaloneParagraphs(header.getParagraphs(), false);
            validateDirectiveTables(header.getTables());
        }
        for (XWPFFooter footer : document.getFooterList()) {
            validateStandaloneParagraphs(footer.getParagraphs(), false);
            validateDirectiveTables(footer.getTables());
        }
    }

    private void validateDirectiveTables(List<XWPFTable> tables) {
        for (XWPFTable table : tables) {
            for (XWPFTableRow row : table.getRows()) {
                String rowText = rowText(row);
                boolean index = rowText.contains(REFERENCE_INDEX_DIRECTIVE);
                boolean text = rowText.contains(REFERENCE_TEXT_DIRECTIVE);
                if (index != text) {
                    throw new IllegalArgumentException(
                            "参考文献重复行必须同时包含 "
                                    + REFERENCE_INDEX_DIRECTIVE + " 和 "
                                    + REFERENCE_TEXT_DIRECTIVE);
                }
                for (XWPFTableCell cell : row.getTableCells()) {
                    validateStandaloneParagraphs(cell.getParagraphs(), index);
                    validateDirectiveTables(cell.getTables());
                }
            }
        }
    }

    private void validateStandaloneParagraphs(
            List<XWPFParagraph> paragraphs, boolean repeatRow) {
        for (XWPFParagraph paragraph : paragraphs) {
            String text = paragraph.getText().strip();
            for (String directive : ALLOWED_DIRECTIVES) {
                if (!text.contains(directive)) continue;
                boolean repeatDirective = directive.equals(REFERENCE_INDEX_DIRECTIVE)
                        || directive.equals(REFERENCE_TEXT_DIRECTIVE);
                if (repeatDirective && !repeatRow) {
                    throw new IllegalArgumentException(
                            "参考文献重复指令只能放在同一个表格行中");
                }
                if (!text.equals(directive)) {
                    throw new IllegalArgumentException(
                            "结构化指令必须独占一个段落：" + directive);
                }
            }
        }
    }

    private void replaceAll(XWPFDocument document, RenderData data) {
        RenderContext context = new RenderContext(document, data);
        replaceTables(document.getTables(), context);
        replaceParagraphs(document.getParagraphs(), context);
        for (XWPFHeader header : document.getHeaderList()) {
            replaceTables(header.getTables(), context);
            replaceParagraphs(header.getParagraphs(), context);
        }
        for (XWPFFooter footer : document.getFooterList()) {
            replaceTables(footer.getTables(), context);
            replaceParagraphs(footer.getParagraphs(), context);
        }
    }

    private void replaceTables(
            List<XWPFTable> tables, RenderContext context) {
        for (XWPFTable table : tables) {
            int rowIndex = 0;
            while (rowIndex < table.getNumberOfRows()) {
                XWPFTableRow row = table.getRow(rowIndex);
                if (rowText(row).contains(REFERENCE_INDEX_DIRECTIVE)) {
                    CTRow templateRow = (CTRow) row.getCtRow().copy();
                    table.removeRow(rowIndex);
                    List<String> references = context.data().collection(
                            "${research.references}");
                    for (int itemIndex = 0;
                         itemIndex < references.size(); itemIndex++) {
                        XWPFTableRow renderedRow = new XWPFTableRow(
                                (CTRow) templateRow.copy(), table);
                        Map<String, String> rowValues = new LinkedHashMap<>(
                                context.data().textValues());
                        rowValues.put(REFERENCE_INDEX_DIRECTIVE,
                                Integer.toString(itemIndex + 1));
                        rowValues.put(REFERENCE_TEXT_DIRECTIVE,
                                references.get(itemIndex));
                        replaceRow(renderedRow, rowValues, context);
                        table.addRow(renderedRow, rowIndex + itemIndex);
                    }
                    rowIndex += references.size();
                    continue;
                }
                for (XWPFTableCell cell : row.getTableCells()) {
                    replaceTables(cell.getTables(), context);
                    replaceParagraphs(cell.getParagraphs(), context);
                }
                rowIndex++;
            }
        }
    }

    private void replaceRow(
            XWPFTableRow row,
            Map<String, String> values,
            RenderContext context) {
        for (XWPFTableCell cell : row.getTableCells()) {
            replacePlainParagraphs(cell.getParagraphs(), values);
            replaceTables(cell.getTables(), context);
        }
    }

    private void replaceParagraphs(
            List<XWPFParagraph> paragraphs, RenderContext context) {
        for (XWPFParagraph paragraph : new ArrayList<>(paragraphs)) {
            String original = paragraph.getText().strip();
            if (LIST_DIRECTIVES.containsKey(original)) {
                renderList(paragraph,
                        context.data().collection(LIST_DIRECTIVES.get(original)),
                        context);
                continue;
            }
            if (LOGO_DIRECTIVE.equals(original)) {
                renderLogo(paragraph, context.data().logo());
                continue;
            }
            replacePlainParagraph(paragraph, context.data().textValues());
        }
    }

    private void replacePlainParagraphs(
            List<XWPFParagraph> paragraphs, Map<String, String> values) {
        for (XWPFParagraph paragraph : new ArrayList<>(paragraphs)) {
            replacePlainParagraph(paragraph, values);
        }
    }

    private void replacePlainParagraph(
            XWPFParagraph paragraph, Map<String, String> values) {
        String original = paragraph.getText();
        if (original == null || values.keySet().stream()
                .noneMatch(original::contains)) return;
        String rendered = original;
        for (Map.Entry<String, String> value : values.entrySet()) {
            rendered = rendered.replace(
                    value.getKey(), value.getValue() == null ? "" : value.getValue());
        }
        RunStyle style = RunStyle.from(paragraph);
        clearRuns(paragraph);
        writeText(paragraph, rendered, style);
    }

    private void renderList(
            XWPFParagraph templateParagraph,
            List<String> items,
            RenderContext context) {
        RunStyle style = RunStyle.from(templateParagraph);
        CTPPr paragraphProperties = templateParagraph.getCTP().isSetPPr()
                ? (CTPPr) templateParagraph.getCTP().getPPr().copy() : null;
        clearRuns(templateParagraph);
        if (items.isEmpty()) {
            return;
        }
        XWPFParagraph current = templateParagraph;
        for (int index = 0; index < items.size(); index++) {
            if (index > 0) {
                current = insertParagraphAfter(current);
                if (paragraphProperties != null) {
                    current.getCTP().setPPr(
                            (CTPPr) paragraphProperties.copy());
                }
            }
            current.setNumID(context.bulletNumberingId());
            current.setNumILvl(BigInteger.ZERO);
            clearRuns(current);
            writeText(current, items.get(index), style);
        }
    }

    private XWPFParagraph insertParagraphAfter(XWPFParagraph paragraph) {
        try (XmlCursor cursor = paragraph.getCTP().newCursor()) {
            cursor.toEndToken();
            cursor.toNextToken();
            IBody body = paragraph.getBody();
            if (body instanceof XWPFDocument document) {
                return document.insertNewParagraph(cursor);
            }
            if (body instanceof XWPFTableCell cell) {
                return cell.insertNewParagraph(cursor);
            }
            if (body instanceof XWPFHeaderFooter headerFooter) {
                return headerFooter.insertNewParagraph(cursor);
            }
            throw new IllegalArgumentException(
                    "列表指令位于不受支持的 Word 容器中");
        }
    }

    private void renderLogo(XWPFParagraph paragraph, LogoImage logo) {
        RunStyle style = RunStyle.from(paragraph);
        clearRuns(paragraph);
        if (logo == null) return;
        LogoDescriptor descriptor = inspectLogo(logo);
        XWPFRun run = paragraph.createRun();
        style.apply(run);
        try {
            run.addPicture(
                    new ByteArrayInputStream(logo.content()),
                    descriptor.pictureType(),
                    logo.fileName(),
                    Units.pixelToEMU(descriptor.renderWidth()),
                    Units.pixelToEMU(descriptor.renderHeight()));
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法写入 Logo 图片", exception);
        }
    }

    private LogoDescriptor inspectLogo(LogoImage logo) {
        byte[] content = logo.content();
        if (content.length == 0 || content.length > MAX_LOGO_BYTES) {
            throw new IllegalArgumentException("Logo 图片必须小于等于 1MB");
        }
        int pictureType;
        if (isPng(content)) {
            pictureType = Document.PICTURE_TYPE_PNG;
        } else if (isJpeg(content)) {
            pictureType = Document.PICTURE_TYPE_JPEG;
        } else {
            throw new IllegalArgumentException("Logo 仅允许 PNG 或 JPEG 格式");
        }
        try {
            BufferedImage image = ImageIO.read(new ByteArrayInputStream(content));
            if (image == null || image.getWidth() <= 0 || image.getHeight() <= 0) {
                throw new IllegalArgumentException("Logo 图片内容无效");
            }
            if (image.getWidth() > MAX_LOGO_PIXELS
                    || image.getHeight() > MAX_LOGO_PIXELS) {
                throw new IllegalArgumentException(
                        "Logo 图片宽高不能超过 2000 像素");
            }
            double scale = Math.min(
                    1.0,
                    Math.min(
                            (double) MAX_LOGO_WIDTH / image.getWidth(),
                            (double) MAX_LOGO_HEIGHT / image.getHeight()));
            return new LogoDescriptor(
                    pictureType,
                    Math.max(1, (int) Math.round(image.getWidth() * scale)),
                    Math.max(1, (int) Math.round(image.getHeight() * scale)));
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("无法读取 Logo 图片", exception);
        }
    }

    private boolean isPng(byte[] content) {
        byte[] signature = new byte[]{
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a};
        return content.length >= signature.length
                && Arrays.equals(
                Arrays.copyOf(content, signature.length), signature);
    }

    private boolean isJpeg(byte[] content) {
        return content.length >= 3
                && (content[0] & 0xff) == 0xff
                && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff;
    }

    private void clearRuns(XWPFParagraph paragraph) {
        for (int index = paragraph.getRuns().size() - 1;
             index >= 0; index--) {
            paragraph.removeRun(index);
        }
    }

    private void writeText(
            XWPFParagraph paragraph, String value, RunStyle style) {
        XWPFRun run = paragraph.createRun();
        style.apply(run);
        String[] lines = (value == null ? "" : value).split("\\R", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) run.addBreak();
            run.setText(lines[index]);
        }
    }

    private String rowText(XWPFTableRow row) {
        return row.getTableCells().stream()
                .map(XWPFTableCell::getText)
                .reduce("", (left, right) -> left + "\n" + right);
    }

    private record RunStyle(
            boolean bold,
            boolean italic,
            int fontSize,
            String fontFamily
    ) {
        private static RunStyle from(XWPFParagraph paragraph) {
            if (paragraph.getRuns().isEmpty()) {
                return new RunStyle(false, false, 10, "Microsoft YaHei");
            }
            XWPFRun source = paragraph.getRuns().getFirst();
            int sourceSize = source.getFontSize();
            String sourceFamily = source.getFontFamily();
            return new RunStyle(
                    source.isBold(),
                    source.isItalic(),
                    sourceSize > 0 ? sourceSize : 10,
                    sourceFamily == null ? "Microsoft YaHei" : sourceFamily);
        }

        private void apply(XWPFRun run) {
            run.setBold(bold);
            run.setItalic(italic);
            run.setFontFamily(fontFamily);
            run.setFontSize(fontSize);
        }
    }

    private static final class RenderContext {
        private final XWPFDocument document;
        private final RenderData data;
        private BigInteger bulletNumberingId;

        private RenderContext(XWPFDocument document, RenderData data) {
            this.document = document;
            this.data = data;
        }

        private RenderData data() {
            return data;
        }

        private BigInteger bulletNumberingId() {
            if (bulletNumberingId != null) return bulletNumberingId;
            XWPFNumbering numbering = document.getNumbering();
            if (numbering == null) numbering = document.createNumbering();
            CTAbstractNum abstractNum = CTAbstractNum.Factory.newInstance();
            BigInteger nextAbstractId = numbering.getAbstractNums().stream()
                    .map(value -> value.getCTAbstractNum().getAbstractNumId())
                    .filter(value -> value != null)
                    .max(BigInteger::compareTo)
                    .orElse(BigInteger.valueOf(-1))
                    .add(BigInteger.ONE);
            abstractNum.setAbstractNumId(nextAbstractId);
            CTLvl level = abstractNum.addNewLvl();
            level.setIlvl(BigInteger.ZERO);
            level.addNewStart().setVal(BigInteger.ONE);
            level.addNewNumFmt().setVal(STNumberFormat.BULLET);
            level.addNewLvlText().setVal("\uf0b7");
            level.addNewLvlJc().setVal(
                    org.openxmlformats.schemas.wordprocessingml.x2006.main
                            .STJc.LEFT);
            level.addNewPPr().addNewInd()
                    .setLeft(BigInteger.valueOf(720));
            level.getPPr().getInd()
                    .setHanging(BigInteger.valueOf(360));
            var bulletFonts = level.addNewRPr().addNewRFonts();
            bulletFonts.setAscii("Symbol");
            bulletFonts.setHAnsi("Symbol");
            BigInteger abstractId = numbering.addAbstractNum(
                    new XWPFAbstractNum(abstractNum));
            bulletNumberingId = numbering.addNum(abstractId);
            return bulletNumberingId;
        }
    }

    public record RenderData(
            Map<String, String> textValues,
            Map<String, List<String>> collections,
            LogoImage logo
    ) {
        public RenderData {
            Map<String, String> safeTextValues = new LinkedHashMap<>();
            if (textValues != null) {
                textValues.forEach((key, value) ->
                        safeTextValues.put(key, value == null ? "" : value));
            }
            textValues = Map.copyOf(safeTextValues);
            Map<String, List<String>> safeCollections = new LinkedHashMap<>();
            if (collections != null) {
                collections.forEach((key, value) ->
                        safeCollections.put(
                                key,
                                value == null ? List.of() : List.copyOf(value)));
            }
            collections = Map.copyOf(safeCollections);
        }

        public static RenderData empty() {
            return new RenderData(Map.of(), Map.of(), null);
        }

        public static RenderData fromTextValues(Map<String, String> values) {
            return fromTextValues(values, null);
        }

        public static RenderData fromTextValues(
                Map<String, String> values, LogoImage logo) {
            Map<String, String> safeValues =
                    values == null ? Map.of() : values;
            Map<String, List<String>> collections = new LinkedHashMap<>();
            Set<String> sources = new LinkedHashSet<>(LIST_DIRECTIVES.values());
            sources.add("${research.references}");
            for (String source : sources) {
                collections.put(source, splitItems(safeValues.get(source)));
            }
            return new RenderData(safeValues, collections, logo);
        }

        public List<String> collection(String sourcePlaceholder) {
            return collections.getOrDefault(sourcePlaceholder, List.of());
        }

        private static List<String> splitItems(String value) {
            if (value == null || value.isBlank()) return List.of();
            return value.lines()
                    .map(String::strip)
                    .map(item -> LIST_PREFIX.matcher(item).replaceFirst(""))
                    .filter(item -> !item.isBlank())
                    .toList();
        }
    }

    public record LogoImage(byte[] content, String fileName) {
        public LogoImage {
            content = content == null ? new byte[0] : content.clone();
            fileName = fileName == null || fileName.isBlank()
                    ? "logo" : fileName.strip();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }

    private record LogoDescriptor(
            int pictureType,
            int renderWidth,
            int renderHeight
    ) {}

    public record ValidationResult(
            List<String> placeholders,
            String message
    ) {}
}
