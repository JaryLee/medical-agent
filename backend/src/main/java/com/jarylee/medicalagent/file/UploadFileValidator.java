package com.jarylee.medicalagent.file;

import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipInputStream;

@Component
public class UploadFileValidator {
    private static final long MAX_SIZE = 20L * 1024 * 1024;
    private static final Set<String> ALLOWED_EXTENSIONS = Set.of("pdf", "docx", "txt", "md");
    private static final Map<String, Set<String>> ALLOWED_MIME = Map.of(
            "pdf", Set.of("application/pdf"),
            "docx", Set.of("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            "txt", Set.of("text/plain"),
            "md", Set.of("text/markdown", "text/plain")
    );
    private static final byte[] PDF_MAGIC = {'%', 'P', 'D', 'F', '-'};
    private static final byte[] ZIP_MAGIC = {'P', 'K', 3, 4};

    public ValidatedFile validate(String originalName, String contentType, byte[] content) {
        if (content == null || content.length == 0) throw new IllegalArgumentException("文件不能为空");
        if (content.length > MAX_SIZE) throw new IllegalArgumentException("文件不能超过 20MB");
        String safeName = sanitizeName(originalName);
        String extension = extensionOf(safeName);
        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("仅支持 .pdf、.docx、.txt 和 .md 文件");
        }
        String normalizedMime = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
        if (!ALLOWED_MIME.get(extension).contains(normalizedMime)) {
            throw new IllegalArgumentException("文件 MIME 类型与扩展名不匹配");
        }
        if ("pdf".equals(extension) && !startsWith(content, PDF_MAGIC)) {
            throw new IllegalArgumentException("PDF 文件魔数校验失败");
        }
        if ("docx".equals(extension) && !startsWith(content, ZIP_MAGIC)) {
            throw new IllegalArgumentException("DOCX 文件魔数校验失败");
        }
        if ("docx".equals(extension) && !isDocxPackage(content)) {
            throw new IllegalArgumentException("DOCX 包结构校验失败");
        }
        String text = null;
        if ("txt".equals(extension) || "md".equals(extension)) {
            text = decodeUtf8(content);
        }
        return new ValidatedFile(safeName, extension, normalizedMime, content.clone(), text);
    }

    private String sanitizeName(String originalName) {
        if (originalName == null || originalName.isBlank()) throw new IllegalArgumentException("缺少文件名");
        String normalized = originalName.replace('\\', '/');
        String baseName = normalized.substring(normalized.lastIndexOf('/') + 1)
                .replaceAll("[\\p{Cntrl}:*?\"<>|]", "_").trim();
        if (baseName.isBlank() || baseName.length() > 180) throw new IllegalArgumentException("文件名不合法");
        return baseName;
    }

    private String extensionOf(String name) {
        int separator = name.lastIndexOf('.');
        if (separator < 1 || separator == name.length() - 1) return "";
        return name.substring(separator + 1).toLowerCase(Locale.ROOT);
    }

    private String decodeUtf8(byte[] content) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(content)).toString();
        } catch (Exception exception) {
            throw new IllegalArgumentException("文本文件必须使用 UTF-8 编码");
        }
    }

    private boolean startsWith(byte[] content, byte[] expected) {
        if (content.length < expected.length) return false;
        for (int index = 0; index < expected.length; index++) {
            if (content[index] != expected[index]) return false;
        }
        return true;
    }

    private boolean isDocxPackage(byte[] content) {
        boolean hasContentTypes = false;
        boolean hasDocument = false;
        int entries = 0;
        try (var zip = new ZipInputStream(new ByteArrayInputStream(content))) {
            for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                if (++entries > 500) return false;
                String name = entry.getName().replace('\\', '/');
                if ("[Content_Types].xml".equals(name)) hasContentTypes = true;
                if ("word/document.xml".equals(name)) hasDocument = true;
            }
            return hasContentTypes && hasDocument;
        } catch (Exception exception) {
            return false;
        }
    }

    public record ValidatedFile(String safeName, String extension, String contentType,
                                byte[] content, String extractedText) {}
}
