package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfSystemProperty(named = "liveCrossref", matches = "true")
class CrossrefApiLiveTest {
    @Test
    void resolvesKnownDoiUsingPublicPoolWithoutInventedMailto() {
        var gateway = new CrossrefRestMetadataGateway(
                new ObjectMapper(), RestClient.builder(), "https://api.crossref.org",
                "medical-research-agent-live-test/1.0", "",
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3,
                Duration.ofHours(24), Duration.ofMillis(250));

        var result = gateway.lookup("10.1056/NEJMoa2204233");

        assertThat(result.found()).isTrue();
        assertThat(result.work().doi()).isEqualTo("10.1056/nejmoa2204233");
        assertThat(result.work().title()).contains("Empagliflozin");
        assertThat(result.externalRequestCount()).isPositive();
    }
}
