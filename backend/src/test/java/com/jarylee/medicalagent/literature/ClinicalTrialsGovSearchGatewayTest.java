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

class ClinicalTrialsGovSearchGatewayTest {
    private final ObjectMapper json = new ObjectMapper();
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
    void mapsValidatedV2StudyAndCachesIdenticalQuery() {
        server.stubFor(get(urlPathEqualTo("/studies"))
                .willReturn(okJson(validResponse())));
        var gateway = gateway(2);

        var first = gateway.search("diabetes AND kidney", 20);
        var cached = gateway.search("diabetes AND kidney", 20);

        assertThat(first.totalResultCount()).isEqualTo(17);
        assertThat(first.trials()).singleElement().satisfies(trial -> {
            assertThat(trial.nctId()).isEqualTo("NCT03594110");
            assertThat(trial.interventions()).contains("DRUG: Empagliflozin");
            assertThat(trial.primaryOutcomes()).contains("Kidney disease progression");
            assertThat(trial.linkedPmids()).containsExactly("36331190");
            assertThat(trial.evidenceScope()).isEqualTo("REGISTRY_RESULTS_AVAILABLE");
            assertThat(trial.verified()).isTrue();
        });
        assertThat(first.externalRequestCount()).isEqualTo(1);
        assertThat(first.cacheHit()).isFalse();
        assertThat(cached.externalRequestCount()).isZero();
        assertThat(cached.cacheHit()).isTrue();
        server.verify(1, getRequestedFor(urlPathEqualTo("/studies"))
                .withQueryParam("query.term", equalTo("diabetes AND kidney"))
                .withQueryParam("pageSize", equalTo("20"))
                .withQueryParam("countTotal", equalTo("true"))
                .withQueryParam("format", equalTo("json"))
                .withHeader("User-Agent", equalTo("medical-agent-test/1.0")));
    }

    @Test
    void retriesTransientFailureAndRejectsDuplicateNctId() {
        server.stubFor(get(urlPathEqualTo("/studies"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        server.stubFor(get(urlPathEqualTo("/studies"))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(validResponse())));

        assertThat(gateway(2).search("kidney", 10).externalRequestCount()).isEqualTo(2);

        server.resetAll();
        server.stubFor(get(urlPathEqualTo("/studies"))
                .willReturn(okJson(validResponse().replace(
                        "\"studies\":[", "\"studies\":[").replace(
                        "],\"totalCount\":17",
                        "," + studyJson() + "],\"totalCount\":17"))));

        assertThatThrownBy(() -> gateway(1).search("duplicate", 10))
                .isInstanceOf(
                        ClinicalTrialsGovSearchGateway.ClinicalTrialsSearchException.class)
                .hasMessageContaining("重复NCT ID");
    }

    private ClinicalTrialsGovSearchGateway gateway(int attempts) {
        return new ClinicalTrialsGovSearchGateway(
                json, RestClient.builder(), server.baseUrl(), "medical-agent-test/1.0",
                Duration.ofSeconds(1), Duration.ofSeconds(2), attempts,
                Duration.ofHours(12), Duration.ZERO);
    }

    private String validResponse() {
        return "{\"studies\":[" + studyJson() + "],\"totalCount\":17}";
    }

    private String studyJson() {
        return """
                {
                  "protocolSection":{
                    "identificationModule":{
                      "nctId":"NCT03594110",
                      "briefTitle":"The Study of Heart and Kidney Protection",
                      "officialTitle":"EMPA-KIDNEY"
                    },
                    "statusModule":{
                      "overallStatus":"COMPLETED",
                      "startDateStruct":{"date":"2019-01-29"},
                      "completionDateStruct":{"date":"2022-07-05"}
                    },
                    "designModule":{
                      "studyType":"INTERVENTIONAL",
                      "phases":["PHASE3"],
                      "enrollmentInfo":{"count":6609}
                    },
                    "conditionsModule":{"conditions":["Chronic Kidney Disease"]},
                    "armsInterventionsModule":{
                      "interventions":[{"type":"DRUG","name":"Empagliflozin"}]
                    },
                    "descriptionModule":{"briefSummary":"Registry summary"},
                    "outcomesModule":{
                      "primaryOutcomes":[{"measure":"Kidney disease progression"}]
                    },
                    "sponsorCollaboratorsModule":{
                      "leadSponsor":{"name":"Boehringer Ingelheim"}
                    },
                    "contactsLocationsModule":{
                      "locations":[{"country":"United Kingdom"},{"country":"United States"}]
                    },
                    "referencesModule":{
                      "references":[{"pmid":"36331190"}]
                    }
                  },
                  "derivedSection":{"miscInfoModule":{"versionHolder":"2026-07-27"}},
                  "hasResults":true
                }
                """.strip();
    }
}
