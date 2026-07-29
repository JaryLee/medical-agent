package com.jarylee.medicalagent.audit;

import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Objects;

@Repository
@Profile("memory")
public class MemoryAuditRepository implements AuditRepository {
    private final PlatformStore store;

    public MemoryAuditRepository(PlatformStore store) {
        this.store = store;
    }

    @Override
    public void save(AuditData audit) {
        store.audits.add(new PlatformStore.AuditRow(audit.hospitalId(), audit.actorId(),
                audit.action(), audit.resourceType(), audit.resourceId(), audit.occurredAt()));
    }

    @Override
    public List<AuditData> findRecent(java.util.UUID hospitalId, int limit) {
        synchronized (store.audits) {
            return store.audits.stream()
                    .filter(row -> hospitalId == null || Objects.equals(row.hospitalId(), hospitalId))
                    .sorted(java.util.Comparator.comparing(PlatformStore.AuditRow::occurredAt).reversed())
                    .limit(limit)
                    .map(row -> new AuditData(java.util.UUID.randomUUID(), row.hospitalId(), row.actorId(),
                            row.action(), row.resourceType(), row.resourceId(), row.occurredAt()))
                    .toList();
        }
    }
}
