package com.jarylee.medicalagent.workflow;

import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class StatisticalAnalysisModels {
    private StatisticalAnalysisModels() {}

    public record SampleSizeParameter(
            String code,
            String label,
            boolean required,
            String valueStatus,
            String value,
            String unit,
            String rationale
    ) {}

    public record StatisticalDraft(
            String schemaVersion,
            UUID draftId,
            UUID protocolId,
            Instant generatedAt,
            StudyType studyType,
            String primaryOutcome,
            String outcomeTypeStatus,
            List<String> descriptiveAnalysis,
            List<String> primaryAnalysisCandidates,
            List<String> secondaryAnalysis,
            List<String> covariates,
            List<String> potentialConfounders,
            List<String> stratifiedAnalyses,
            List<String> subgroupAnalyses,
            List<String> sensitivityAnalyses,
            List<String> missingDataPlan,
            List<String> multipleComparisonPlan,
            List<String> modelDiagnostics,
            List<String> effectMeasureCandidates,
            String confidenceIntervalPlan,
            List<SampleSizeParameter> sampleSizeParameters,
            List<String> recommendedSoftware,
            List<String> issuesToConfirm,
            ResearchProtocolModels.ProtocolSection statisticalSectionVersion,
            String inputSha256,
            String generatorVersion,
            List<String> limitations
    ) {}
}
