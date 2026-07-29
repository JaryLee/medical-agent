package com.jarylee.medicalagent.agent.model;

public interface ModelRouter {
    ResearchModel route(LogicalModelType logicalModelType);
}
