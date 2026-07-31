package com.jarylee.medicalagent.auth;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

public interface IdentityRepository {
    Optional<HospitalData> findHospitalByCode(String code);
    Optional<HospitalData> findHospitalById(UUID id);
    List<HospitalData> findHospitals();
    void insertHospital(HospitalData hospital);

    Optional<UserData> findUser(UUID hospitalId, String username);
    Optional<UserData> findUserById(UUID id);
    Optional<UserData> findUserById(UUID hospitalId, UUID id);
    List<UserData> findUsers();
    List<UserData> findUsers(UUID hospitalId);
    void insertUser(UserData user);
    void updateUserState(UserData user);

    void insertSession(String tokenHash, UUID userId, Instant expiresAt);
    Optional<SessionData> findSession(String tokenHash);
    void revokeSession(String tokenHash);
    void revokeSessionsByUser(UUID userId);

    record HospitalData(UUID id, String code, String name, Instant createdAt) {}
    record UserData(UUID id, UUID hospitalId, String username, String passwordHash,
                    Set<Role> roles, boolean enabled, boolean forcePasswordChange,
                    int failedAttempts, Instant lockedUntil) {}
    record SessionData(UUID userId, Instant expiresAt) {}
}
