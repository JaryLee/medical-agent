package com.jarylee.medicalagent.file;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class UploadFileValidatorTest {
    private final UploadFileValidator validator = new UploadFileValidator();

    @Test
    void acceptsUtf8TextAndRemovesPathSegments() {
        var result = validator.validate("../patient-notes.txt", "text/plain",
                "研究材料".getBytes(StandardCharsets.UTF_8));
        assertThat(result.safeName()).isEqualTo("patient-notes.txt");
        assertThat(result.extractedText()).isEqualTo("研究材料");
    }

    @Test
    void rejectsExtensionMimeAndMagicMismatches() {
        assertThatThrownBy(() -> validator.validate("patients.xlsx", "application/octet-stream", new byte[]{1}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("仅支持");
        assertThatThrownBy(() -> validator.validate("fake.pdf", "application/pdf", "not-pdf".getBytes()))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("魔数");
        assertThatThrownBy(() -> validator.validate("fake.docx",
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                new byte[]{'P', 'K', 3, 4, 0, 0, 0, 0}))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("包结构");
    }
}
