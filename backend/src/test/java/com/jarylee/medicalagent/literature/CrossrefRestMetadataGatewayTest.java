package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CrossrefRestMetadataGatewayTest {
    private WireMockServer server;

    @BeforeEach
    void startServer() {
        server = new WireMockServer(0);
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.stop();
    }

    @Test
    void mapsExactDoiMetadataAndCachesResponse() {
        server.stubFor(get(urlPathEqualTo("/works/10.1056/nejmoa2204233"))
                .willReturn(okJson(validResponse())));
        var gateway = gateway("research@example.org", 2);

        var first = gateway.lookup("https://doi.org/10.1056/NEJMoa2204233");
        var cached = gateway.lookup("10.1056/nejmoa2204233");

        assertThat(first.found()).isTrue();
        assertThat(first.work().title())
                .isEqualTo("Empagliflozin in Patients with Chronic Kidney Disease");
        assertThat(first.work().authors())
                .containsExactly("The EMPA-KIDNEY Collaborative Group");
        assertThat(first.work().publicationDate()).isEqualTo("2023-1-12");
        assertThat(first.externalRequestCount()).isEqualTo(1);
        assertThat(cached.cacheHit()).isTrue();
        assertThat(cached.externalRequestCount()).isZero();
        server.verify(1, getRequestedFor(
                        urlPathEqualTo("/works/10.1056/nejmoa2204233"))
                .withQueryParam("mailto", equalTo("research@example.org"))
                .withHeader("User-Agent", equalTo("medical-agent-test/1.0")));
    }

    @Test
    void retriesTransientResponseAndTreats404AsNotFound() {
        server.stubFor(get(urlPathEqualTo("/works/10.1056/nejmoa2204233"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo("/works/10.1056/nejmoa2204233"))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(validResponse())));
        assertThat(gateway("", 2).lookup("10.1056/nejmoa2204233")
                .externalRequestCount()).isEqualTo(2);

        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/works/10.9999/missing"))
                .willReturn(aResponse().withStatus(404)));
        var missing = gateway("", 1).lookup("10.9999/missing");
        assertThat(missing.found()).isFalse();
        assertThat(missing.externalRequestCount()).isEqualTo(1);
    }

    @Test
    void rejectsResponseForDifferentDoi() {
        server.stubFor(get(anyUrl()).willReturn(okJson(
                validResponse().replace("10.1056/NEJMoa2204233", "10.1056/wrong"))));

        assertThatThrownBy(() -> gateway("", 1).lookup("10.1056/nejmoa2204233"))
                .isInstanceOf(
                        CrossrefRestMetadataGateway.CrossrefMetadataException.class)
                .hasMessageContaining("DOI");
    }

    private CrossrefRestMetadataGateway gateway(String mailto, int attempts) {
        return new CrossrefRestMetadataGateway(
                new ObjectMapper(), RestClient.builder(), server.baseUrl(),
                "medical-agent-test/1.0", mailto, Duration.ofSeconds(1),
                Duration.ofSeconds(2), attempts, Duration.ofHours(24), Duration.ZERO);
    }

    private String validResponse() {
        return """
                {
                  "status":"ok",
                  "message":{
                    "DOI":"10.1056/NEJMoa2204233",
                    "title":["Empagliflozin in Patients with Chronic Kidney Disease"],
                    "author":[{"name":"The EMPA-KIDNEY Collaborative Group"}],
                    "container-title":["New England Journal of Medicine"],
                    "published":{"date-parts":[[2023,1,12]]},
                    "type":"journal-article",
                    "publisher":"Massachusetts Medical Society"
                  }
                }
                """;
    }
}
