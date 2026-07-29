package com.jarylee.medicalagent.literature;

public interface PubMedSearchGateway {
    PubMedSearchModels.GatewayResult search(String query, int maxResults);
}
