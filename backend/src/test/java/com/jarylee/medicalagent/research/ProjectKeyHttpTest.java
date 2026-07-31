package com.jarylee.medicalagent.research;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.auth.SessionAuthenticationFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ProjectKeyHttpTest {
    private static final String PASSWORD = "InitialPass123";

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityRepository identities;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void publicKeyLookupHidesUuidAndDoesNotRevealCrossHospitalExistence()
            throws Exception {
        UUID suffix = UUID.randomUUID();
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalA, "KEY-A-" + suffix.toString().toUpperCase(),
                "Project key hospital A", Instant.now()));
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalB, "KEY-B-" + suffix.toString().toUpperCase(),
                "Project key hospital B", Instant.now()));
        identities.insertUser(user(UUID.randomUUID(), hospitalA));
        identities.insertUser(user(UUID.randomUUID(), hospitalB));

        Cookie sessionA = login("KEY-A-" + suffix, "doctor");
        Cookie sessionB = login("KEY-B-" + suffix, "doctor");
        var first = createProject(sessionA, "SAME-CODE", "同名课题", "idem-a-" + suffix);
        var second = createProject(sessionB, "SAME-CODE", "同名课题", "idem-b-" + suffix);

        String projectKeyA = first.path("projectKey").asText();
        assertThat(ProjectKey.isValid(projectKeyA)).isTrue();
        assertThat(ProjectKey.isValid(second.path("projectKey").asText())).isTrue();

        mvc.perform(get("/api/research/projects/{projectKey}", projectKeyA)
                        .cookie(sessionA))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.projectKey").value(projectKeyA))
                .andExpect(jsonPath("$.data.id").doesNotExist())
                .andExpect(jsonPath("$.data.hospitalId").doesNotExist());

        assertProjectNotFound(sessionB, projectKeyA);
        assertProjectNotFound(sessionA, "bad-key");
        assertProjectNotFound(
                sessionA, "prj_2123456789ABCDEFGHJKMNPQRS");
    }

    private IdentityRepository.UserData user(UUID id, UUID hospitalId) {
        return new IdentityRepository.UserData(
                id, hospitalId, "doctor", passwordEncoder.encode(PASSWORD),
                Set.of(Role.DOCTOR), true, false, 0, null);
    }

    private Cookie login(String hospitalCode, String username) throws Exception {
        var result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "hospitalCode":"%s",
                                  "username":"%s",
                                  "password":"%s"
                                }
                                """.formatted(hospitalCode, username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(SessionAuthenticationFilter.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private com.fasterxml.jackson.databind.JsonNode createProject(
            Cookie session, String code, String name, String idempotencyKey)
            throws Exception {
        var response = mvc.perform(post("/api/research/projects")
                        .with(csrf())
                        .cookie(session)
                        .header("Idempotency-Key", idempotencyKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"code":"%s","name":"%s"}
                                """.formatted(code, name)))
                .andExpect(status().isOk())
                .andReturn();
        return json.readTree(response.getResponse().getContentAsString())
                .path("data");
    }

    private void assertProjectNotFound(Cookie session, String projectKey)
            throws Exception {
        mvc.perform(get("/api/research/projects/{projectKey}", projectKey)
                        .cookie(session))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"))
                .andExpect(jsonPath("$.error.message").value("课题不存在"));
    }
}
