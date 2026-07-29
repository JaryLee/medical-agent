package com.jarylee.medicalagent.audit;

import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Service
public class AuditQueryService {
    private final AuditRepository repository;
    private final CurrentUserProvider currentUser;

    public AuditQueryService(AuditRepository repository, CurrentUserProvider currentUser) {
        this.repository = repository;
        this.currentUser = currentUser;
    }

    public List<AuditView> recent(int requestedLimit) {
        AuthenticatedUser actor = currentUser.requireUser();
        int limit = Math.max(1, Math.min(requestedLimit, 200));
        UUID hospitalScope;
        if (actor.hasRole(Role.AUDIT_ADMIN)) {
            hospitalScope = actor.hospitalId();
        } else if (actor.hasRole(Role.HOSPITAL_ADMIN)) {
            hospitalScope = actor.hospitalId();
        } else {
            throw BusinessException.forbidden("无权查看审计日志");
        }
        return repository.findRecent(hospitalScope, limit).stream()
                .map(row -> new AuditView(row.id(), row.hospitalId(), row.actorId(), row.action(),
                        row.resourceType(), row.resourceId(), row.occurredAt()))
                .toList();
    }

    public record AuditView(UUID id, UUID hospitalId, UUID actorId, String action,
                            String resourceType, String resourceId, Instant occurredAt) {}
}
