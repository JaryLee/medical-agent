package com.jarylee.medicalagent.literature;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
@ConditionalOnProperty(
        name = "medical.clinical-trials.mode", havingValue = "mock", matchIfMissing = true)
public class MockClinicalTrialsSearchGateway implements ClinicalTrialsSearchGateway {
    private final ObjectMapper json;

    public MockClinicalTrialsSearchGateway(ObjectMapper json) {
        this.json = json;
    }

    @Override
    public ClinicalTrialsSearchModels.GatewayResult search(String query, int maxResults) {
        List<ClinicalTrialsSearchModels.Trial> records = List.of(
                new ClinicalTrialsSearchModels.Trial(
                        "NCT03594110",
                        "The Study of Heart and Kidney Protection With Empagliflozin",
                        "A Multinational, Randomized, Parallel Group, Double-blind Study",
                        "COMPLETED", "INTERVENTIONAL", List.of("PHASE3"),
                        List.of("Chronic Kidney Disease"),
                        List.of("DRUG: Empagliflozin", "DRUG: Placebo"),
                        "Deterministic public registry snapshot for workflow testing.",
                        List.of("Kidney disease progression or cardiovascular death"),
                        "Boehringer Ingelheim", "2019-01-29", "2022-07-05",
                        6609, List.of("United Kingdom", "United States"), true,
                        "REGISTRY_RESULTS_AVAILABLE", true,
                        "CLINICAL_TRIALS_GOV_MOCK_SNAPSHOT", List.of("36331190")),
                new ClinicalTrialsSearchModels.Trial(
                        "NCT03036150",
                        "Dapagliflozin And Prevention of Adverse Outcomes in Chronic Kidney Disease",
                        "A Study to Evaluate the Effect of Dapagliflozin on Renal Outcomes",
                        "COMPLETED", "INTERVENTIONAL", List.of("PHASE3"),
                        List.of("Chronic Kidney Disease"),
                        List.of("DRUG: Dapagliflozin", "DRUG: Placebo"),
                        "Deterministic public registry snapshot for workflow testing.",
                        List.of("Composite renal and cardiovascular outcome"),
                        "AstraZeneca", "2017-02-02", "2020-06-11",
                        4304, List.of("International"), true,
                        "REGISTRY_RESULTS_AVAILABLE", true,
                        "CLINICAL_TRIALS_GOV_MOCK_SNAPSHOT", List.of("32970396"))
        ).stream().limit(Math.max(0, maxResults)).toList();
        try {
            byte[] raw = json.writeValueAsBytes(Map.of(
                    "schema", "clinicaltrials-api-v2-mock/v1",
                    "query", query,
                    "studies", records));
            return new ClinicalTrialsSearchModels.GatewayResult(
                    records.size(), records, raw, "application/json",
                    "clinicaltrials-api-v2-mock/v1", 0,
                    "2026-07-27", false);
        } catch (Exception exception) {
            throw new IllegalStateException("Mock ClinicalTrials.gov响应序列化失败", exception);
        }
    }
}
