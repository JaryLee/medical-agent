package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "medical.crossref.mode", havingValue = "mock", matchIfMissing = true)
public class MockCrossrefMetadataGateway implements CrossrefMetadataGateway {
    private final ObjectMapper json;

    public MockCrossrefMetadataGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public CrossrefMetadataModels.GatewayResult lookup(String doi) {
        String normalized = doi.strip().toLowerCase(Locale.ROOT);
        CrossrefMetadataModels.Work work = switch (normalized) {
            case "10.1056/nejmoa2204233" -> new CrossrefMetadataModels.Work(
                    normalized,
                    "Empagliflozin in Patients with Chronic Kidney Disease",
                    List.of("The EMPA-KIDNEY Collaborative Group"),
                    "New England Journal of Medicine", "2023-01-12",
                    "journal-article", "Massachusetts Medical Society");
            case "10.1056/nejmoa2024816" -> new CrossrefMetadataModels.Work(
                    normalized,
                    "Dapagliflozin in Patients with Chronic Kidney Disease",
                    List.of("Heerspink HJL", "Stefánsson BV", "Correa-Rotter R"),
                    "New England Journal of Medicine", "2020-10-08",
                    "journal-article", "Massachusetts Medical Society");
            default -> null;
        };
        try {
            byte[] raw = json.writeValueAsBytes(Map.of(
                    "status", work == null ? "not-found" : "ok",
                    "doi", normalized,
                    "message", work == null ? Map.of() : work));
            return new CrossrefMetadataModels.GatewayResult(
                    work != null, work, raw, "application/json",
                    "crossref-rest-mock/v1", 0, false);
        } catch (Exception exception) {
            throw new IllegalStateException("Mock Crossref响应序列化失败", exception);
        }
    }
}
