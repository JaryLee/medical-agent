package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NcbiPubMedSearchGatewayTest {
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
    void postsSearchSummaryAndFetchAndValidatesPmidTitleMapping() {
        stubSuccessfulPipeline();

        var result = gateway(3).search(
                "(diabetes[MeSH Terms]) AND (cohort study[Title/Abstract])", 20);

        assertThat(result.totalResultCount()).isEqualTo(2);
        assertThat(result.articles()).hasSize(2)
                .allMatch(article -> article.verified()
                        && "PUBMED_EUTILS".equals(article.source()));
        assertThat(result.articles().getFirst().pmid()).isEqualTo("123");
        assertThat(result.articles().getFirst().doi()).isEqualTo("10.1000/test.123");
        assertThat(result.articles().getFirst().abstractText()).contains("BACKGROUND:");
        assertThat(result.externalRequestCount()).isEqualTo(3);
        assertThat(new String(result.rawResponse(), StandardCharsets.UTF_8))
                .contains("ncbi-eutils-raw/v1", "efetchXml");

        server.verify(postRequestedFor(urlEqualTo("/esearch.fcgi"))
                .withRequestBody(containing("usehistory=y"))
                .withRequestBody(containing("tool=medical_agent_test"))
                .withRequestBody(containing("email=developer%40example.org")));
        server.verify(postRequestedFor(urlEqualTo("/esummary.fcgi"))
                .withRequestBody(containing("id=123%2C456")));
        server.verify(postRequestedFor(urlEqualTo("/efetch.fcgi"))
                .withRequestBody(containing("rettype=abstract")));
    }

    @Test
    void retriesOnlyTransientHttpFailure() {
        server.stubFor(post(urlEqualTo("/esearch.fcgi"))
                .inScenario("retry")
                .whenScenarioStateIs(STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withStatus(503)));
        server.stubFor(post(urlEqualTo("/esearch.fcgi"))
                .inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson("""
                        {"esearchresult":{"count":"0","idlist":[],"webenv":"w","querykey":"1"}}
                        """)));

        var result = gateway(2).search("diabetes", 10);

        assertThat(result.totalResultCount()).isZero();
        assertThat(result.externalRequestCount()).isEqualTo(1);
        server.verify(2, postRequestedFor(urlEqualTo("/esearch.fcgi")));
    }

    @Test
    void failsClosedOnMissingEmailAndMismatchedTitle() {
        assertThatThrownBy(() -> new NcbiPubMedSearchGateway(
                json, RestClient.builder(), server.baseUrl(), "medical_agent_test", "",
                "", Duration.ofSeconds(1), Duration.ofSeconds(2), 1, Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("PUBMED_EMAIL");

        stubSuccessfulPipeline();
        server.stubFor(post(urlEqualTo("/esummary.fcgi"))
                .atPriority(1)
                .willReturn(okJson("""
                        {"result":{"uids":["123","456"],
                          "123":{"uid":"123","title":"Wrong title"},
                          "456":{"uid":"456","title":"Second verified article"}}}
                        """)));

        assertThatThrownBy(() -> gateway(1).search("diabetes", 20))
                .isInstanceOf(NcbiPubMedSearchGateway.PubMedSearchException.class)
                .hasMessageContaining("标题映射");
    }

    private NcbiPubMedSearchGateway gateway(int attempts) {
        return new NcbiPubMedSearchGateway(
                json, RestClient.builder(), server.baseUrl(),
                "medical_agent_test", "developer@example.org", "",
                Duration.ofSeconds(1), Duration.ofSeconds(2), attempts, Duration.ZERO);
    }

    private void stubSuccessfulPipeline() {
        server.stubFor(post(urlEqualTo("/esearch.fcgi"))
                .willReturn(okJson("""
                        {"esearchresult":{
                          "count":"2",
                          "idlist":["123","456"],
                          "webenv":"NCBI_HISTORY",
                          "querykey":"1"
                        }}
                        """)));
        server.stubFor(post(urlEqualTo("/esummary.fcgi"))
                .willReturn(okJson("""
                        {"result":{"uids":["123","456"],
                          "123":{"uid":"123","title":"First verified article."},
                          "456":{"uid":"456","title":"Second verified article"}
                        }}
                        """)));
        server.stubFor(post(urlEqualTo("/efetch.fcgi"))
                .willReturn(okXml("""
                        <?xml version="1.0" encoding="UTF-8"?>
                        <!DOCTYPE PubmedArticleSet SYSTEM "http://127.0.0.1:1/pubmed.dtd">
                        <PubmedArticleSet>
                          <PubmedArticle>
                            <MedlineCitation>
                              <PMID>123</PMID>
                              <Article>
                                <ArticleTitle>First verified article</ArticleTitle>
                                <Abstract><AbstractText Label="BACKGROUND">First abstract.</AbstractText></Abstract>
                                <AuthorList><Author><LastName>Zhang</LastName><Initials>A</Initials></Author></AuthorList>
                                <Journal>
                                  <JournalIssue><PubDate><Year>2026</Year><Month>Jul</Month><Day>1</Day></PubDate></JournalIssue>
                                  <Title>Test Journal</Title>
                                </Journal>
                              </Article>
                            </MedlineCitation>
                            <PubmedData><ArticleIdList>
                              <ArticleId IdType="pubmed">123</ArticleId>
                              <ArticleId IdType="doi">10.1000/test.123</ArticleId>
                            </ArticleIdList></PubmedData>
                          </PubmedArticle>
                          <PubmedArticle>
                            <MedlineCitation>
                              <PMID>456</PMID>
                              <Article>
                                <ArticleTitle>Second verified article</ArticleTitle>
                                <AuthorList><Author><CollectiveName>Study Group</CollectiveName></Author></AuthorList>
                                <Journal>
                                  <JournalIssue><PubDate><MedlineDate>2025 Winter</MedlineDate></PubDate></JournalIssue>
                                  <Title>Second Journal</Title>
                                </Journal>
                              </Article>
                            </MedlineCitation>
                            <PubmedData><ArticleIdList>
                              <ArticleId IdType="pubmed">456</ArticleId>
                            </ArticleIdList></PubmedData>
                          </PubmedArticle>
                        </PubmedArticleSet>
                        """)));
    }
}
