package com.jarylee.medicalagent.agent.deepseek;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.ResearchOutputValidator;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry.VersionedPrompt;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(named = "RUN_DEEPSEEK_LIVE_TEST", matches = "true")
class DeepSeekApiLiveTest {
    @Test
    void returnsValidMedicalResearchAnalysis() {
        String tokenFile = System.getenv("DEEPSEEK_TOKEN_FILE");
        DeepSeekResearchModel model = new DeepSeekResearchModel(
                new ObjectMapper(),
                RestClient.builder(),
                true,
                "https://api.deepseek.com",
                "deepseek-v4-flash",
                "",
                tokenFile,
                Duration.ofSeconds(10),
                Duration.ofSeconds(90));

        var result = model.analyzeIdea(
                "拟使用虚构的医院历史数据库，研究2型糖尿病成年患者使用SGLT2抑制剂与一年内eGFR变化的关联。",
                new VersionedPrompt(
                        "STEP_01_PARSE_IDEA",
                        "research-idea-analysis/v1",
                        "分析以下研究想法，未知信息必须标记为待确认：${input}"));

        new ResearchOutputValidator().validate(result);
        assertThat(model.provider()).isEqualTo("deepseek");
        assertThat(result.profile().clinicalProblem()).isNotBlank();
    }
}
