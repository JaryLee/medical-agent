package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.file.MemoryObjectStorage;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchConcept;
import com.jarylee.medicalagent.literature.SearchStrategyService.SearchStrategy;
import org.junit.jupiter.api.Test;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LiteratureSearchServiceTest {
    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Clock clock = Clock.fixed(
            Instant.parse("2026-07-28T01:00:00Z"), ZoneOffset.UTC);

    @Test
    void storesReplayableRawResponseAndMatchingSha256() throws Exception {
        var repository = new MemoryLiteratureSearchRepository();
        var storage = new MemoryObjectStorage();
        byte[] raw = "{\"esearch\":{\"count\":1}}".getBytes();
        PubMedSearchGateway gateway = (query, max) ->
                new PubMedSearchModels.GatewayResult(
                        1, List.of(article()), raw, "application/json",
                        "ncbi-eutils/v1", 3, "history", "1");
        var service = new LiteratureSearchService(
                gateway, repository, storage, json, clock, 20);
        UUID hospitalId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        var result = service.execute(
                hospitalId, projectId, UUID.randomUUID(), strategy());

        String expectedHash = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(raw));
        assertThat(result.rawResponseSha256()).isEqualTo(expectedHash);
        assertThat(result.records()).hasSize(1);
        var persisted = repository.all().getFirst();
        assertThat(persisted.status()).isEqualTo("COMPLETED");
        assertThat(persisted.rawResponseSha256()).isEqualTo(expectedHash);
        assertThat(storage.get(persisted.rawObjectKey())).isEqualTo(raw);
    }

    @Test
    void recordsFailureAndDoesNotGenerateFakeResult() {
        var repository = new MemoryLiteratureSearchRepository();
        PubMedSearchGateway gateway = (query, max) -> {
            throw new NcbiPubMedSearchGateway.PubMedSearchException(
                    "PUBMED_HTTP_ERROR", "PubMed调用失败，HTTP 503");
        };
        var service = new LiteratureSearchService(
                gateway, repository, new MemoryObjectStorage(), json, clock, 20);

        assertThatThrownBy(() -> service.execute(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), strategy()))
                .isInstanceOf(NcbiPubMedSearchGateway.PubMedSearchException.class)
                .hasMessageContaining("503");
        assertThat(repository.all()).singleElement()
                .satisfies(search -> {
                    assertThat(search.status()).isEqualTo("FAILED");
                    assertThat(search.errorCode()).isEqualTo("PUBMED_HTTP_ERROR");
                    assertThat(search.returnedResultCount()).isNull();
                });
    }

    private SearchStrategy strategy() {
        return new SearchStrategy(
                "search-strategy/v1", "deterministic-peco/v1", "pubmed-query/v1",
                "CONFIRMED", "测试研究问题", List.of("PUBMED"),
                List.of(new SearchConcept(
                        "POPULATION", "研究人群", List.of("diabetes"), true)),
                "diabetes[Title/Abstract]", "diabetes[Title/Abstract]",
                List.of(), List.of());
    }

    private PubMedSearchModels.Article article() {
        return new PubMedSearchModels.Article(
                "123", "10.1000/test", "Verified article", List.of("Zhang A"),
                "Test Journal", "2026", "Abstract", "ABSTRACT_ONLY",
                true, "PUBMED_EUTILS");
    }
}
