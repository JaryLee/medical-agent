package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "medical.pubmed.mode", havingValue = "mock", matchIfMissing = true)
public class MockPubMedSearchGateway implements PubMedSearchGateway {
    private final ObjectMapper json;

    public MockPubMedSearchGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public PubMedSearchModels.GatewayResult search(String query, int maxResults) {
        List<PubMedSearchModels.Article> records = List.of(
                new PubMedSearchModels.Article(
                        "36331190", "10.1056/NEJMoa2204233",
                        "Empagliflozin in Patients with Chronic Kidney Disease",
                        List.of("The EMPA-KIDNEY Collaborative Group"),
                        "New England Journal of Medicine", "2023-01-12",
                        "Mock abstract snapshot for deterministic workflow testing.",
                        "ABSTRACT_ONLY", true, "PUBMED_MOCK_SNAPSHOT"),
                new PubMedSearchModels.Article(
                        "32970396", "10.1056/NEJMoa2024816",
                        "Dapagliflozin in Patients with Chronic Kidney Disease",
                        List.of("Heerspink HJL", "Stefánsson BV", "Correa-Rotter R"),
                        "New England Journal of Medicine", "2020-10-08",
                        "Mock abstract snapshot for deterministic workflow testing.",
                        "ABSTRACT_ONLY", true, "PUBMED_MOCK_SNAPSHOT")
        ).stream().limit(Math.max(0, maxResults)).toList();
        try {
            byte[] raw = json.writeValueAsBytes(Map.of(
                    "source", "PUBMED_MOCK_SNAPSHOT",
                    "query", query,
                    "records", records));
            return new PubMedSearchModels.GatewayResult(
                    records.size(), records, raw, "application/json",
                    "pubmed-mock/v1", 0, null, null);
        } catch (Exception exception) {
            throw new IllegalStateException("Mock PubMed响应序列化失败", exception);
        }
    }
}
