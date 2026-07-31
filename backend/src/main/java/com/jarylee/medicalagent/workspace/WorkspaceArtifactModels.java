package com.jarylee.medicalagent.workspace;

import com.jarylee.medicalagent.workspace.WorkspaceModels.AllowedAction;
import com.jarylee.medicalagent.workspace.WorkspaceModels.LabeledCode;

import java.util.List;
import java.util.Map;

public final class WorkspaceArtifactModels {
    private WorkspaceArtifactModels() {}

    public record ArtifactSectionView(
            String projectKey,
            String sectionCode,
            String title,
            LabeledCode status,
            Map<String, Object> content,
            List<AllowedAction> allowedActions,
            String disclaimer) {}

    public record ArtifactDownload(
            String fileName,
            String contentType,
            byte[] content,
            String contentSha256) {}
}
