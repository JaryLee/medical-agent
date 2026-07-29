package com.jarylee.medicalagent.workflow;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class ClaimCitationValidationModels {
    private ClaimCitationValidationModels() {}

    public record CitationLink(
            UUID linkId,
            UUID claimId,
            int linkOrder,
            String sourceType,
            String pmid,
            String doi,
            String title,
            String supportLevel,
            String evidenceScope,
            String evidenceExcerpt,
            String excerptLocation,
            String excerptSha256,
            String citationValidationStatus,
            String manualConfirmationStatus
    ) {}

    public record ResearchClaim(
            UUID claimId,
            UUID sectionId,
            String sectionCode,
            int claimOrder,
            String claimType,
            String claimText,
            String supportStatus,
            String expertConfirmationStatus,
            List<CitationLink> citationLinks,
            List<String> issuesToConfirm
    ) {}

    public record ValidationResult(
            String schemaVersion,
            UUID validationTaskId,
            UUID protocolId,
            Instant validatedAt,
            int claimCount,
            int citationLinkCount,
            int abstractOnlyClaimCount,
            int needsExpertReviewClaimCount,
            List<ResearchClaim> claims,
            String inputSha256,
            String validatorVersion,
            List<String> limitations
    ) {}
}
