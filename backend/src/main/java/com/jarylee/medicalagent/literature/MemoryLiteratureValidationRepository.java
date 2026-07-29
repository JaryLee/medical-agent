package com.jarylee.medicalagent.literature;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryLiteratureValidationRepository implements LiteratureValidationRepository {
    private final Map<UUID, ValidationData> validations = new ConcurrentHashMap<>();

    @Override
    public void create(ValidationData validation) {
        if (validations.putIfAbsent(validation.id(), validation) != null) {
            throw new IllegalStateException("文献验证任务已存在");
        }
    }

    @Override
    public void complete(
            ValidationData validation,
            List<LiteratureValidationModels.CitationValidation> citations,
            List<LiteratureValidationModels.EvidenceLink> evidenceLinks) {
        validations.compute(validation.id(), (id, current) -> {
            if (current == null || !"RUNNING".equals(current.status())) {
                throw new IllegalStateException("文献验证任务当前不可完成");
            }
            return validation;
        });
    }

    @Override
    public void fail(
            UUID hospitalId, UUID validationId, String errorCode,
            String errorMessage, Instant completedAt) {
        validations.computeIfPresent(validationId, (id, current) -> new ValidationData(
                current.id(), current.hospitalId(), current.projectId(),
                current.agentTaskId(), "FAILED", current.startedAt(), completedAt,
                null, null, null, null, null, null, null,
                null, errorCode, truncate(errorMessage)));
    }

    List<ValidationData> all() {
        return List.copyOf(validations.values());
    }

    private String truncate(String value) {
        if (value == null || value.isBlank()) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
