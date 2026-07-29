package com.jarylee.medicalagent.auth;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "medical.bootstrap.admin-username=platform-admin",
        "medical.bootstrap.admin-password=BootstrapPass123"
})
@AutoConfigureMockMvc
class AuthenticationHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;

    @Test
    void cookieAuthenticationCsrfAndHospitalAdministrationWorkTogether() throws Exception {
        mvc.perform(post("/api/prototype/ideas/analyze")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"匿名研究想法\"}"))
                .andExpect(status().isForbidden());

        var login = mvc.perform(post("/api/auth/login")
                        .header("X-Trace-Id", "client-trace-123")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"platform-admin","password":"BootstrapPass123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Trace-Id", "client-trace-123"))
                .andExpect(jsonPath("$.traceId").value("client-trace-123"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.containsString("SameSite=Strict")))
                .andReturn();

        Cookie session = login.getResponse().getCookie(SessionAuthenticationFilter.COOKIE_NAME);
        assertThat(session).isNotNull();

        mvc.perform(get("/api/runtime/model").cookie(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.provider").value("mock"))
                .andExpect(jsonPath("$.data.mode").value("mock"))
                .andExpect(jsonPath("$.data.externalEnabled").value(false))
                .andExpect(jsonPath("$.data.apiKey").doesNotExist())
                .andExpect(jsonPath("$.data.apiKeyFile").doesNotExist());

        mvc.perform(post("/api/prototype/ideas/analyze")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"匿名研究想法\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/admin/hospitals")
                        .cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HOSP-A\",\"name\":\"医院A\"}"))
                .andExpect(status().isForbidden());

        var hospitalResponse = mvc.perform(post("/api/admin/hospitals")
                        .with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"HOSP-A\",\"name\":\"医院A\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.code").value("HOSP-A"))
                .andReturn();
        JsonNode hospital = mapper.readTree(hospitalResponse.getResponse().getContentAsString()).path("data");

        mvc.perform(post("/api/hospital/users")
                        .with(csrf()).cookie(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                  "hospitalId":"%s",
                                  "username":"doctor-a",
                                  "initialPassword":"InitialPass123",
                                  "roles":["DOCTOR"]
                                }
                                """.formatted(hospital.path("id").asText())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.hospitalId").value(hospital.path("id").asText()))
                .andExpect(jsonPath("$.data.forcePasswordChange").value(true));

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.info.title").value("医疗研究 Agent API"))
                .andExpect(jsonPath("$.paths['/api/research/projects']").exists());
    }
}
