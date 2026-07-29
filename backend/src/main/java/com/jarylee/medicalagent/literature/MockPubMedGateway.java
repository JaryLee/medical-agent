package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.agent.model.ResearchModels.LiteratureRecord;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class MockPubMedGateway implements LiteratureGateway {
    @Override
    public List<LiteratureRecord> search(String query) {
        return List.of(
                new LiteratureRecord("CIT-001", "36331190", "10.1056/NEJMoa2204233",
                        "Empagliflozin in Patients with Chronic Kidney Disease",
                        List.of("The EMPA-KIDNEY Collaborative Group"),
                        "New England Journal of Medicine", "2023-01-12",
                        "ABSTRACT_ONLY", true, "PUBMED_MOCK_SNAPSHOT"),
                new LiteratureRecord("CIT-002", "32970396", "10.1056/NEJMoa2024816",
                        "Dapagliflozin in Patients with Chronic Kidney Disease",
                        List.of("Heerspink HJL", "Stefánsson BV", "Correa-Rotter R"),
                        "New England Journal of Medicine", "2020-10-08",
                        "ABSTRACT_ONLY", true, "PUBMED_MOCK_SNAPSHOT"));
    }
}
