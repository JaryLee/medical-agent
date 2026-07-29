package com.jarylee.medicalagent.literature;

public interface CrossrefMetadataGateway {
    CrossrefMetadataModels.GatewayResult lookup(String doi);
}
