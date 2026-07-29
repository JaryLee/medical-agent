package com.jarylee.medicalagent.common;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfiguration {
    @Bean
    OpenAPI medicalAgentOpenApi() {
        String schemeName = "medicalSession";
        return new OpenAPI()
                .info(new Info()
                        .title("医疗研究 Agent API")
                        .version("0.1.0")
                        .description("阶段 1 工程接口；系统不用于诊断或治疗。写操作还需要 XSRF-TOKEN。"))
                .components(new Components().addSecuritySchemes(schemeName,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.COOKIE)
                                .name("MEDICAL_SESSION")))
                .addSecurityItem(new SecurityRequirement().addList(schemeName));
    }
}
