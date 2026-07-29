package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.web.client.RestClient;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

@EnabledIfEnvironmentVariable(
        named = "PUBMED_EMAIL", matches = "[^\\s@]+@[^\\s@]+\\.[^\\s@]+")
class NcbiPubMedApiLiveTest {

    @Test
    void retrievesAndValidatesKnownPmidThroughRealEutilities() {
        var gateway = new NcbiPubMedSearchGateway(
                new ObjectMapper(), RestClient.builder(),
                System.getenv().getOrDefault(
                        "PUBMED_BASE_URL",
                        "https://eutils.ncbi.nlm.nih.gov/entrez/eutils"),
                System.getenv().getOrDefault(
                        "PUBMED_TOOL", "medical_research_agent_validation"),
                System.getenv("PUBMED_EMAIL"),
                System.getenv().getOrDefault("PUBMED_API_KEY", ""),
                Duration.ofSeconds(5), Duration.ofSeconds(30), 3);

        var result = gateway.search("\"36331190\"[PMID]", 1);

        assertThat(result.articles()).singleElement()
                .satisfies(article -> {
                    assertThat(article.pmid()).isEqualTo("36331190");
                    assertThat(article.title()).isNotBlank();
                    assertThat(article.verified()).isTrue();
                });
        assertThat(result.rawResponse()).isNotEmpty();
        assertThat(result.externalRequestCount()).isEqualTo(3);
    }
}
