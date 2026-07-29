package com.jarylee.medicalagent.audit;

import com.jarylee.medicalagent.auth.AuthenticatedUser;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.UUID;

@Service
public class AuditService {
    private final AuditRepository repository;

    public AuditService(AuditRepository repository) {
        this.repository = repository;
    }

    public void record(AuthenticatedUser actor, String action, String type, String resourceId) {
        repository.save(new AuditRepository.AuditData(UUID.randomUUID(), actor.hospitalId(),
                actor.userId(), action, type, resourceId, Instant.now()));
    }

    public void recordSystem(java.util.UUID hospitalId, String action, String type, String resourceId) {
        repository.save(new AuditRepository.AuditData(UUID.randomUUID(), hospitalId,
                null, action, type, resourceId, Instant.now()));
    }
}
