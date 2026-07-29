package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "liveClinicalTrials", matches = "true")
class ClinicalTrialsGovApiLiveTest {
    @Test
    void retrievesKnownRegistryRecordFromOfficialV2Api() {
        var gateway = new ClinicalTrialsGovSearchGateway(
                new ObjectMapper(), RestClient.builder(),
                "https://clinicaltrials.gov/api/v2", "medical-research-agent-live-test/1.0",
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3,
                Duration.ofMinutes(1), Duration.ofMillis(100));

        var result = gateway.search("NCT03594110", 10);

        assertThat(result.totalResultCount()).isPositive();
        assertThat(result.trials())
                .anySatisfy(trial -> {
                    assertThat(trial.nctId()).isEqualTo("NCT03594110");
                    assertThat(trial.briefTitle()).isNotBlank();
                    assertThat(trial.verified()).isTrue();
                });
        assertThat(result.rawResponse()).isNotEmpty();
        assertThat(result.toolVersion()).isEqualTo("clinicaltrials-api-v2");
    }
}
