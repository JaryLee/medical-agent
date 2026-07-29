package com.jarylee.medicalagent.auth;

import com.jarylee.medicalagent.infrastructure.PlatformStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("memory")
public class MemoryIdentityRepository implements IdentityRepository {
    private final PlatformStore store;

    public MemoryIdentityRepository(PlatformStore store) {
        this.store = store;
    }

    @Override
    public Optional<HospitalData> findHospitalByCode(String code) {
        return store.hospitals.values().stream()
                .filter(row -> row.code().equalsIgnoreCase(code)).findFirst().map(this::hospital);
    }

    @Override
    public Optional<HospitalData> findHospitalById(UUID id) {
        return Optional.ofNullable(store.hospitals.get(id)).map(this::hospital);
    }

    @Override
    public List<HospitalData> findHospitals() {
        return store.hospitals.values().stream().map(this::hospital)
                .sorted(Comparator.comparing(HospitalData::code)).toList();
    }

    @Override
    public void insertHospital(HospitalData hospital) {
        store.hospitals.put(hospital.id(), new PlatformStore.HospitalRow(
                hospital.id(), hospital.code(), hospital.name(), hospital.createdAt()));
    }

    @Override
    public Optional<UserData> findUser(UUID hospitalId, String username) {
        return store.users.values().stream()
                .filter(row -> Objects.equals(row.hospitalId, hospitalId)
                        && row.username.equalsIgnoreCase(username))
                .findFirst().map(this::user);
    }

    @Override
    public Optional<UserData> findUserById(UUID id) {
        return Optional.ofNullable(store.users.get(id)).map(this::user);
    }

    @Override
    public List<UserData> findUsers() {
        return store.users.values().stream().map(this::user).toList();
    }

    @Override
    public void insertUser(UserData user) {
        var row = new PlatformStore.UserRow(user.id(), user.hospitalId(), user.username(),
                user.passwordHash(), user.roles());
        apply(row, user);
        store.users.put(row.id, row);
    }

    @Override
    public void updateUserState(UserData user) {
        var row = store.users.get(user.id());
        if (row != null) apply(row, user);
    }

    @Override
    public void insertSession(String tokenHash, UUID userId, java.time.Instant expiresAt) {
        store.sessions.put(tokenHash, new PlatformStore.SessionRow(userId, expiresAt));
    }

    @Override
    public Optional<SessionData> findSession(String tokenHash) {
        return Optional.ofNullable(store.sessions.get(tokenHash))
                .map(row -> new SessionData(row.userId(), row.expiresAt()));
    }

    @Override public void revokeSession(String tokenHash) { store.sessions.remove(tokenHash); }

    @Override
    public void revokeSessionsByUser(UUID userId) {
        store.sessions.entrySet().removeIf(entry -> entry.getValue().userId().equals(userId));
    }

    private HospitalData hospital(PlatformStore.HospitalRow row) {
        return new HospitalData(row.id(), row.code(), row.name(), row.createdAt());
    }

    private UserData user(PlatformStore.UserRow row) {
        return new UserData(row.id, row.hospitalId, row.username, row.passwordHash, row.roles,
                row.enabled, row.forcePasswordChange, row.failedAttempts, row.lockedUntil);
    }

    private void apply(PlatformStore.UserRow row, UserData user) {
        row.passwordHash = user.passwordHash();
        row.enabled = user.enabled();
        row.forcePasswordChange = user.forcePasswordChange();
        row.failedAttempts = user.failedAttempts();
        row.lockedUntil = user.lockedUntil();
    }
}
