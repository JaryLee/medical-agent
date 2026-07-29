package com.jarylee.medicalagent.auth;

import com.jarylee.medicalagent.common.BusinessException;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;

@Repository
@Profile("postgres")
public class JdbcIdentityRepository implements IdentityRepository {
    private static final String USER_SELECT = """
            select u.id,u.hospital_id,u.username,u.password_hash,u.enabled,u.force_password_change,
                   u.failed_login_attempts,u.locked_until,
                   coalesce(string_agg(ur.role_code, ',' order by ur.role_code),'') role_codes
            from platform_user u left join user_role ur on ur.user_id=u.id
            """;
    private final JdbcClient jdbc;

    public JdbcIdentityRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<HospitalData> findHospitalByCode(String code) {
        return jdbc.sql("select id,code,name,created_at from hospital where lower(code)=lower(:code)")
                .param("code", code).query(this::mapHospital).optional();
    }

    @Override
    public Optional<HospitalData> findHospitalById(UUID id) {
        return jdbc.sql("select id,code,name,created_at from hospital where id=:id")
                .param("id", id).query(this::mapHospital).optional();
    }

    @Override
    public List<HospitalData> findHospitals() {
        return jdbc.sql("select id,code,name,created_at from hospital order by code")
                .query(this::mapHospital).list();
    }

    @Override
    public void insertHospital(HospitalData hospital) {
        try {
            jdbc.sql("insert into hospital(id,code,name,created_at) values(:id,:code,:name,:createdAt)")
                    .params(Map.of("id", hospital.id(), "code", hospital.code(),
                            "name", hospital.name(), "createdAt", Timestamp.from(hospital.createdAt()))).update();
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("医院编码已存在");
        }
    }

    @Override
    public Optional<UserData> findUser(UUID hospitalId, String username) {
        String hospitalPredicate = hospitalId == null ? "u.hospital_id is null" : "u.hospital_id=:hospitalId";
        var query = jdbc.sql(USER_SELECT + """
                where %s and lower(u.username)=lower(:username)
                group by u.id
                """.formatted(hospitalPredicate)).param("username", username);
        if (hospitalId != null) query = query.param("hospitalId", hospitalId);
        return query.query(this::mapUser).optional();
    }

    @Override
    public Optional<UserData> findUserById(UUID id) {
        return jdbc.sql(USER_SELECT + " where u.id=:id group by u.id")
                .param("id", id).query(this::mapUser).optional();
    }

    @Override
    public List<UserData> findUsers() {
        return jdbc.sql(USER_SELECT + " group by u.id order by u.username").query(this::mapUser).list();
    }

    @Override
    @Transactional
    public void insertUser(UserData user) {
        try {
            jdbc.sql("""
                    insert into platform_user(
                        id,hospital_id,username,password_hash,enabled,force_password_change,
                        failed_login_attempts,locked_until
                    ) values(:id,:hospitalId,:username,:passwordHash,:enabled,:forcePasswordChange,
                             :failedAttempts,:lockedUntil)
                    """).params(params(user)).update();
            for (Role role : user.roles()) {
                jdbc.sql("insert into user_role(user_id,role_code) values(:userId,:role)")
                        .params(Map.of("userId", user.id(), "role", role.name())).update();
            }
        } catch (DataIntegrityViolationException exception) {
            throw BusinessException.conflict("本院用户名已存在");
        }
    }

    @Override
    public void updateUserState(UserData user) {
        jdbc.sql("""
                update platform_user set password_hash=:passwordHash,enabled=:enabled,
                    force_password_change=:forcePasswordChange,
                    failed_login_attempts=:failedAttempts,locked_until=:lockedUntil,
                    version=version+1 where id=:id
                """).params(params(user)).update();
    }

    @Override
    public void insertSession(String tokenHash, UUID userId, Instant expiresAt) {
        jdbc.sql("insert into user_session(id,user_id,token_hash,expires_at) values(:id,:userId,:hash,:expiresAt)")
                .params(Map.of("id", UUID.randomUUID(), "userId", userId,
                        "hash", tokenHash, "expiresAt", Timestamp.from(expiresAt))).update();
    }

    @Override
    public Optional<SessionData> findSession(String tokenHash) {
        return jdbc.sql("""
                select user_id,expires_at from user_session
                where token_hash=:hash and revoked_at is null
                """).param("hash", tokenHash)
                .query((rs, row) -> new SessionData(
                        rs.getObject("user_id", UUID.class), rs.getTimestamp("expires_at").toInstant()))
                .optional();
    }

    @Override
    public void revokeSession(String tokenHash) {
        jdbc.sql("update user_session set revoked_at=current_timestamp where token_hash=:hash and revoked_at is null")
                .param("hash", tokenHash).update();
    }

    @Override
    public void revokeSessionsByUser(UUID userId) {
        jdbc.sql("update user_session set revoked_at=current_timestamp where user_id=:userId and revoked_at is null")
                .param("userId", userId).update();
    }

    private Map<String, Object> params(UserData user) {
        Map<String, Object> params = new HashMap<>();
        params.put("id", user.id());
        params.put("hospitalId", user.hospitalId());
        params.put("username", user.username());
        params.put("passwordHash", user.passwordHash());
        params.put("enabled", user.enabled());
        params.put("forcePasswordChange", user.forcePasswordChange());
        params.put("failedAttempts", user.failedAttempts());
        params.put("lockedUntil", user.lockedUntil() == null ? null : Timestamp.from(user.lockedUntil()));
        return params;
    }

    private HospitalData mapHospital(ResultSet rs, int row) throws SQLException {
        return new HospitalData(rs.getObject("id", UUID.class), rs.getString("code"),
                rs.getString("name"), rs.getTimestamp("created_at").toInstant());
    }

    private UserData mapUser(ResultSet rs, int row) throws SQLException {
        Set<Role> roles = new HashSet<>();
        String roleCodes = rs.getString("role_codes");
        if (roleCodes != null && !roleCodes.isBlank()) {
            Arrays.stream(roleCodes.split(",")).map(Role::valueOf).forEach(roles::add);
        }
        return new UserData(rs.getObject("id", UUID.class), rs.getObject("hospital_id", UUID.class),
                rs.getString("username"), rs.getString("password_hash"), Set.copyOf(roles),
                rs.getBoolean("enabled"), rs.getBoolean("force_password_change"),
                rs.getInt("failed_login_attempts"),
                rs.getTimestamp("locked_until") == null ? null : rs.getTimestamp("locked_until").toInstant());
    }
}
