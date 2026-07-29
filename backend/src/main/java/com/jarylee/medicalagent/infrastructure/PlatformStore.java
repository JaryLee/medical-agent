package com.jarylee.medicalagent.infrastructure;

import com.jarylee.medicalagent.auth.Role;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class PlatformStore {
    public final Map<UUID, HospitalRow> hospitals = new ConcurrentHashMap<>();
    public final Map<UUID, UserRow> users = new ConcurrentHashMap<>();
    public final Map<String, SessionRow> sessions = new ConcurrentHashMap<>();
    public final Map<UUID, ProjectRow> projects = new ConcurrentHashMap<>();
    public final Map<UUID, FileRow> files = new ConcurrentHashMap<>();
    public final Map<String, ProjectMemberRow> projectMembers = new ConcurrentHashMap<>();
    public final List<AuditRow> audits = Collections.synchronizedList(new ArrayList<>());
    public final Map<String, UUID> idempotency = new ConcurrentHashMap<>();

    public record HospitalRow(UUID id, String code, String name, Instant createdAt) {}

    public static final class UserRow {
        public final UUID id;
        public final UUID hospitalId;
        public final String username;
        public String passwordHash;
        public final Set<Role> roles;
        public boolean enabled = true;
        public boolean forcePasswordChange = true;
        public int failedAttempts;
        public Instant lockedUntil;

        public UserRow(UUID id, UUID hospitalId, String username, String passwordHash, Set<Role> roles) {
            this.id = id;
            this.hospitalId = hospitalId;
            this.username = username;
            this.passwordHash = passwordHash;
            this.roles = Set.copyOf(roles);
        }
    }

    public record SessionRow(UUID userId, Instant expiresAt) {}

    public static final class ProjectRow {
        public final UUID id;
        public final UUID hospitalId;
        public final String code;
        public String name;
        public long version;

        public ProjectRow(UUID id, UUID hospitalId, String code, String name) {
            this.id = id;
            this.hospitalId = hospitalId;
            this.code = code;
            this.name = name;
        }
    }

    public record AuditRow(UUID hospitalId, UUID actorId, String action,
                           String resourceType, String resourceId, Instant occurredAt) {}

    public record FileRow(UUID id, UUID hospitalId, UUID projectId, String originalName,
                          String objectKey, String contentType, long sizeBytes, String sha256,
                          String securityStatus, String matchedRules, String scanEngine,
                          int extractedCharacters, String extractionStatus, Instant createdAt) {}

    public record ProjectMemberRow(UUID hospitalId, UUID projectId, UUID userId, String role) {}
}
