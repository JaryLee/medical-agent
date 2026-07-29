package com.jarylee.medicalagent.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "medical.bootstrap.admin-username=platform-admin",
        "medical.bootstrap.admin-password=BootstrapPass123",
        "medical.security.secure-cookie=false",
        "springdoc.api-docs.enabled=true",
        "springdoc.swagger-ui.enabled=true"
})
@ActiveProfiles({"memory", "prod"})
@AutoConfigureMockMvc
class ProductionSecurityConfigurationTest {
    @Autowired MockMvc mvc;
    @Value("${medical.file-scan.mode}") String fileScanMode;

    @Test
    void productionProfileForcesSecureCookiesAndProtectsDeveloperDocumentation() throws Exception {
        assertThat(fileScanMode).isEqualTo("clamav");

        mvc.perform(get("/v3/api-docs"))
                .andExpect(status().isForbidden());
        mvc.perform(get("/swagger-ui.html"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username":"platform-admin","password":"BootstrapPass123"}
                                """))
                .andExpect(status().isOk())
                .andExpect(header().string("Set-Cookie", containsString("; Secure")));
    }

    @Test
    void healthProbesAreAnonymousAndDoNotExposeComponents() throws Exception {
        mvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
        mvc.perform(get("/actuator/env"))
                .andExpect(status().isForbidden());
    }
}
