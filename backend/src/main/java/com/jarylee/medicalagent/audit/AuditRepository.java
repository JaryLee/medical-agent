package com.jarylee.medicalagent.audit;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditRepository {
    void save(AuditData audit);
    List<AuditData> findRecent(UUID hospitalId, int limit);

    record AuditData(UUID id, UUID hospitalId, UUID actorId, String action,
                     String resourceType, String resourceId, Instant occurredAt) {}
}
