package com.jarylee.medicalagent.literature;

public interface ClinicalTrialsSearchGateway {
    ClinicalTrialsSearchModels.GatewayResult search(String query, int maxResults);
}
