package com.jarylee.medicalagent.workspace;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.auth.SessionAuthenticationFilter;
import com.jarylee.medicalagent.workflow.AgentWorkflowWorker;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "medical.security.secure-cookie=false",
        "medical.agent.worker-initial-delay=1h",
        "medical.workspace.sse-timeout=100ms",
        "medical.workspace.sse-poll-interval=20ms"
})
@AutoConfigureMockMvc
class WorkspaceV2HttpTest {
    private static final String PASSWORD = "InitialPass123";
    private static final Pattern UUID_PATTERN = Pattern.compile(
            "(?i)[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-"
                    + "[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityRepository identities;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired AgentWorkflowWorker worker;

    @Test
    void completesFirstV2SliceWithStablePublicContractAndCommandGuards()
            throws Exception {
        Fixture fixture = fixture("V2-FLOW");
        JsonNode initial = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/workspace-summary",
                fixture.owner());
        assertThat(initial.at("/data/businessStatus/code").asText())
                .isEqualTo("DRAFT");
        assertThat(initial.at("/data/nextAction/code").asText())
                .isEqualTo("START_RESEARCH_IDEA");
        long initialVersion = initial.at("/meta/readModelVersion").asLong();
        assertThat(initialVersion).isEqualTo(1);
        assertNoInternalDetails(initial.toString());

        String startKey = "workspace-start-" + fixture.token();
        String startBody = """
                {"idea":"研究2型糖尿病患者用药与肾功能变化的关联"}
                """;
        String started = action(
                fixture, "START_RESEARCH_IDEA", startKey,
                initialVersion, startBody, 200);
        JsonNode startedJson = json.readTree(started);
        long startedVersion = startedJson.at("/meta/readModelVersion").asLong();
        assertThat(startedVersion).isGreaterThan(initialVersion);
        assertNoInternalDetails(started);

        String replayed = action(
                fixture, "START_RESEARCH_IDEA", startKey,
                initialVersion, startBody, 200);
        assertThat(json.readTree(replayed)).isEqualTo(startedJson);

        String changedPayload = action(
                fixture, "START_RESEARCH_IDEA", startKey,
                initialVersion, """
                        {"idea":"同一幂等键下的不同研究构想"}
                        """, 409);
        assertThat(json.readTree(changedPayload).at("/error/code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        String changedAction = action(
                fixture, "CANCEL_RESEARCH_WORKFLOW", startKey,
                initialVersion, "{}", 409);
        assertThat(json.readTree(changedAction).at("/error/code").asText())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        action(
                fixture, "CANCEL_RESEARCH_WORKFLOW",
                "workspace-stale-" + fixture.token(),
                initialVersion, "{}", 409);

        worker.poll();
        JsonNode clarification = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/idea-direction",
                fixture.owner());
        assertThat(clarification.at("/data/workflowStatus/code").asText())
                .isEqualTo("WAITING_USER");
        assertThat(clarification.at("/data/currentClarificationQuestions").size())
                .isGreaterThan(0);
        assertNoInternalDetails(clarification.toString());

        Map<String, String> answers = new LinkedHashMap<>();
        clarification.at("/data/currentClarificationQuestions")
                .forEach(question -> answers.put(
                        question.asText(), "本院匿名回顾性数据可支持该项信息"));
        long clarificationVersion =
                clarification.at("/meta/readModelVersion").asLong();
        String clarified = action(
                fixture, "SUBMIT_CLARIFICATIONS",
                "workspace-answers-" + fixture.token(),
                clarificationVersion,
                json.writeValueAsString(Map.of("answers", answers)), 200);
        assertNoInternalDetails(clarified);

        worker.poll();
        JsonNode directions = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/idea-direction",
                fixture.owner());
        JsonNode candidates = directions.at("/data/directionCandidates/candidates");
        assertThat(candidates.size()).isEqualTo(3);
        String directionKey = candidates.get(0).path("directionKey").asText();
        assertThat(directionKey).startsWith("dir_");
        assertNoInternalDetails(directions.toString());

        long directionVersion = directions.at("/meta/readModelVersion").asLong();
        String confirmed = action(
                fixture, "CONFIRM_RESEARCH_DIRECTION",
                "workspace-direction-" + fixture.token(),
                directionVersion,
                json.writeValueAsString(Map.of("directionKey", directionKey)),
                200);
        assertNoInternalDetails(confirmed);

        JsonNode stages = getJson(
                "/api/research/projects/" + fixture.projectKey() + "/stages",
                fixture.owner());
        assertThat(stages.path("data").size()).isEqualTo(9);
        assertNoInternalDetails(stages.toString());

        JsonNode searchWaiting = pollUntilAction(
                fixture, "CONFIRM_SEARCH_STRATEGY", 4);
        JsonNode evidence = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/evidence",
                fixture.owner());
        String pubmedQuery = evidence.at(
                "/data/content/searchStrategy/pubmedQuery").asText();
        assertThat(pubmedQuery).isNotBlank();
        assertNoInternalDetails(evidence.toString());
        action(
                fixture,
                "CONFIRM_SEARCH_STRATEGY",
                "workspace-search-" + fixture.token(),
                searchWaiting.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "pubmedQuery",
                        pubmedQuery + "\nNOT animals[MeSH Terms]")),
                200);

        JsonNode designWaiting = pollUntilAction(
                fixture, "CONFIRM_OBSERVATIONAL_DESIGN", 8);
        JsonNode design = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/design",
                fixture.owner());
        String studyType = design.at(
                "/data/content/recommendation/recommendedStudyType").asText();
        String primaryOutcome = design.at(
                "/data/content/recommendation/primaryOutcomeCandidate").asText();
        assertThat(studyType).isIn(
                "CROSS_SECTIONAL", "COHORT", "CASE_CONTROL");
        assertThat(primaryOutcome).isNotBlank();
        assertNoInternalDetails(design.toString());
        action(
                fixture,
                "REQUEST_DESIGN_MODEL_ADVICE",
                "workspace-design-advice-" + fixture.token(),
                designWaiting.at("/meta/readModelVersion").asLong(),
                "{}",
                200);
        JsonNode designAdvice = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/design/model-advice",
                fixture.owner());
        assertThat(designAdvice.at("/data/0/status").asText())
                .isEqualTo("ALIGNED");
        assertThat(designAdvice.at("/data/0/advisoryOnly").asBoolean())
                .isTrue();
        assertThat(designAdvice.at(
                "/data/0/ruleRecommendedStudyType").asText())
                .isEqualTo(studyType);
        assertThat(designAdvice.at(
                "/data/0/modelSelectedStudyType").asText())
                .isEqualTo(studyType);
        assertThat(designAdvice.at("/data/0/conflicts").size())
                .isZero();
        assertNoInternalDetails(designAdvice.toString());
        JsonNode modelUsage = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/model-usage",
                fixture.owner());
        assertThat(modelUsage.at("/data/callCount").asInt())
                .isGreaterThanOrEqualTo(2);
        assertThat(modelUsage.at("/data/calls/0/callKey").asText())
                .startsWith("mcall_");
        assertNoInternalDetails(modelUsage.toString());
        JsonNode modelGovernance = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/model-governance",
                fixture.owner());
        assertThat(modelGovernance.at(
                "/data/externalModelEnabled").asBoolean()).isFalse();
        assertThat(modelGovernance.at("/data/routes").size()).isEqualTo(4);
        assertThat(modelGovernance.at(
                "/data/budget/persisted").asBoolean()).isTrue();
        assertNoInternalDetails(modelGovernance.toString());
        JsonNode afterDesignAdvice = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/workspace-summary",
                fixture.owner());
        action(
                fixture,
                "CONFIRM_OBSERVATIONAL_DESIGN",
                "workspace-design-" + fixture.token(),
                afterDesignAdvice.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "studyType", studyType,
                        "primaryOutcome", primaryOutcome,
                        "authorizeProtocolGeneration", true)),
                200);

        for (int index = 0; index < 5; index++) worker.poll();
        for (String artifact : new String[] {
                "evidence",
                "design",
                "protocol",
                "statistics",
                "quality",
                "internal-review",
                "draft-export"
        }) {
            JsonNode artifactResponse = getJson(
                    "/api/research/projects/" + fixture.projectKey()
                            + "/" + artifact,
                    fixture.owner());
            assertNoInternalDetails(artifactResponse.toString());
        }
        JsonNode protocol = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol",
                fixture.owner());
        assertThat(protocol.at("/data/content/protocol/sections").size())
                .isEqualTo(18);
        assertThat(protocol.at(
                "/data/content/protocol/sections/0/sectionKey").asText())
                .startsWith("sec_");
        JsonNode quality = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/quality",
                fixture.owner());
        assertThat(quality.at("/data/content/strobe/items").size())
                .isEqualTo(22);

        UUID expertId = UUID.randomUUID();
        String expertName = "expert-" + fixture.token();
        identities.insertUser(user(
                expertId,
                fixture.hospitalId(),
                expertName,
                Set.of(Role.EXPERT)));
        Cookie expert = login(fixture.hospitalCode(), expertName);
        addMember(fixture, expertId, "VIEWER");
        JsonNode expertReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                expert);
        JsonNode target = expertReview.at(
                "/data/content/review/commentTargets/0");
        assertThat(target.path("targetKey").asText())
                .startsWith("sec_");
        action(
                fixture.projectKey(),
                expert,
                "ADD_INTERNAL_REVIEW_COMMENT",
                "workspace-review-comment-" + fixture.token(),
                expertReview.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "targetType", target.path("targetType").asText(),
                        "targetKey", target.path("targetKey").asText(),
                        "targetVersion", target.path("targetVersion").asInt(),
                        "commentType", "MEDICAL",
                        "responsibility", "MEDICAL_REVIEW",
                        "content", "请进一步明确匿名病例纳入标准。")),
                200);
        JsonNode reviewAfterComment = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                expert);
        assertThat(reviewAfterComment.at(
                "/data/content/review/comments/0/commentKey").asText())
                .startsWith("cmt_");
        assertThat(reviewAfterComment.at(
                "/data/content/review/comments/0/content").asText())
                .contains("匿名病例纳入标准");
        assertNoInternalDetails(reviewAfterComment.toString());
        action(
                fixture.projectKey(),
                expert,
                "SUBMIT_MEDICAL_REVIEW",
                "workspace-review-return-" + fixture.token(),
                reviewAfterComment.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "decision", "RETURN_FOR_REVISION",
                        "summary", "请按批注意见修订后重新提交。",
                        "reviewVersion",
                        reviewAfterComment.at(
                                "/data/content/review/version").asLong())),
                200);

        JsonNode revisionProtocol = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol",
                fixture.owner());
        JsonNode revisionSection = revisionProtocol.at(
                "/data/content/protocol/sections/0");
        String revisedContent = revisionSection.path("content").asText()
                + "\n\n补充：仅纳入满足预先定义匿名标准的记录。";
        action(
                fixture,
                "UPDATE_PROTOCOL_SECTION",
                "workspace-protocol-edit-" + fixture.token(),
                revisionProtocol.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "sectionKey",
                        revisionSection.path("sectionKey").asText(),
                        "expectedSectionVersion",
                        revisionSection.path("versionNo").asInt(),
                        "content", revisedContent,
                        "changeReason", "依据医学审核批注补充纳入标准")),
                200);
        JsonNode revisedProtocol = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol",
                fixture.owner());
        assertThat(revisedProtocol.at(
                "/data/content/protocol/sections/0/versionHistory").size())
                .isGreaterThanOrEqualTo(2);
        assertThat(revisedProtocol.at(
                "/data/content/protocol/sections/0/content").asText())
                .contains("匿名标准");
        action(
                fixture,
                "SUBMIT_PROTOCOL_REVISION",
                "workspace-protocol-submit-" + fixture.token(),
                revisedProtocol.at("/meta/readModelVersion").asLong(),
                "{}",
                200);
        for (int index = 0; index < 4; index++) worker.poll();
        JsonNode secondReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                fixture.owner());
        assertThat(secondReview.at(
                "/data/content/review/reviewRoundNo").asInt())
                .isEqualTo(2);
        assertNoInternalDetails(secondReview.toString());

        JsonNode secondRoundTarget = secondReview.at(
                "/data/content/review/commentTargets/0");
        action(
                fixture.projectKey(),
                expert,
                "ADD_INTERNAL_REVIEW_COMMENT",
                "workspace-review-round2-comment-" + fixture.token(),
                secondReview.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "targetType",
                        secondRoundTarget.path("targetType").asText(),
                        "targetKey",
                        secondRoundTarget.path("targetKey").asText(),
                        "targetVersion",
                        secondRoundTarget.path("targetVersion").asInt(),
                        "commentType", "MEDICAL",
                        "responsibility", "MEDICAL_REVIEW",
                        "content", "第二轮请补充匿名排除标准。")),
                200);
        JsonNode secondRoundAfterComment = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                expert);
        action(
                fixture.projectKey(),
                expert,
                "SUBMIT_MEDICAL_REVIEW",
                "workspace-review-round2-return-" + fixture.token(),
                secondRoundAfterComment.at(
                        "/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "decision", "RETURN_FOR_REVISION",
                        "summary", "请补充排除标准后再次提交。",
                        "reviewVersion",
                        secondRoundAfterComment.at(
                                "/data/content/review/version").asLong())),
                200);
        JsonNode round2RevisionProtocol = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol",
                fixture.owner());
        action(
                fixture,
                "SUBMIT_PROTOCOL_REVISION",
                "workspace-round2-noop-submit-" + fixture.token(),
                round2RevisionProtocol.at(
                        "/meta/readModelVersion").asLong(),
                "{}",
                409);
        JsonNode round2Section = round2RevisionProtocol.at(
                "/data/content/protocol/sections/0");
        action(
                fixture,
                "GENERATE_PROTOCOL_SECTION_CANDIDATE",
                "workspace-round2-model-generate-" + fixture.token(),
                round2RevisionProtocol.at(
                        "/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "sectionKey",
                        round2Section.path("sectionKey").asText())),
                200);
        JsonNode modelCandidates = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol/model-candidates",
                fixture.owner());
        JsonNode modelCandidate = modelCandidates.at("/data/0");
        assertThat(modelCandidate.path("candidateKey").asText())
                .startsWith("cand_");
        assertThat(modelCandidate.path("status").asText())
                .isEqualTo("VALIDATED");
        assertThat(modelCandidate.path("content").asText())
                .contains("模型辅助候选");
        action(
                fixture,
                "REVIEW_PROTOCOL_SECTION_CANDIDATE",
                "workspace-round2-model-review-" + fixture.token(),
                modelCandidates.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "candidateKey",
                        modelCandidate.path("candidateKey").asText())),
                200);
        JsonNode modelReviews = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol/model-reviews",
                fixture.owner());
        assertThat(modelReviews.at("/data/0/advisoryOnly").asBoolean())
                .isTrue();
        assertThat(modelReviews.at("/data/0/severity").asText())
                .isEqualTo("LOW");
        action(
                fixture,
                "APPLY_PROTOCOL_SECTION_CANDIDATE",
                "workspace-round2-model-apply-" + fixture.token(),
                modelReviews.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "candidateKey",
                        modelCandidate.path("candidateKey").asText(),
                        "expectedCandidateVersion",
                        modelCandidate.path("version").asLong())),
                200);
        JsonNode round2EditedProtocol = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/protocol",
                fixture.owner());
        assertThat(round2EditedProtocol.at(
                "/data/content/protocol/sections/0/origin").asText())
                .isEqualTo("AGENT_MODEL");
        assertThat(round2EditedProtocol.at(
                "/data/content/protocol/sections/0/content").asText())
                .contains("模型辅助候选");
        action(
                fixture,
                "SUBMIT_PROTOCOL_REVISION",
                "workspace-round2-protocol-submit-" + fixture.token(),
                round2EditedProtocol.at(
                        "/meta/readModelVersion").asLong(),
                "{}",
                200);
        for (int index = 0; index < 4; index++) worker.poll();
        JsonNode thirdReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                fixture.owner());
        assertThat(thirdReview.at(
                "/data/content/review/reviewRoundNo").asInt())
                .isEqualTo(3);

        UUID statisticalExpertId = UUID.randomUUID();
        String statisticalExpertName =
                "stat-expert-" + fixture.token();
        identities.insertUser(user(
                statisticalExpertId,
                fixture.hospitalId(),
                statisticalExpertName,
                Set.of(Role.EXPERT)));
        Cookie statisticalExpert = login(
                fixture.hospitalCode(), statisticalExpertName);
        addMember(fixture, statisticalExpertId, "VIEWER");

        JsonNode medicalReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                expert);
        action(
                fixture.projectKey(),
                expert,
                "SUBMIT_MEDICAL_REVIEW",
                "workspace-review-medical-approve-" + fixture.token(),
                medicalReview.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "decision", "APPROVE",
                        "summary", "修订内容符合匿名科研方案医学审核要求。",
                        "reviewVersion",
                        medicalReview.at(
                                "/data/content/review/version").asLong())),
                200);

        JsonNode statisticalReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                statisticalExpert);
        action(
                fixture.projectKey(),
                statisticalExpert,
                "SUBMIT_STATISTICAL_REVIEW",
                "workspace-review-statistical-approve-" + fixture.token(),
                statisticalReview.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "decision", "APPROVE",
                        "summary", "统计分析计划和质量预检查满足草案审核要求。",
                        "reviewVersion",
                        statisticalReview.at(
                                "/data/content/review/version").asLong())),
                200);

        JsonNode ownerReview = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/internal-review",
                fixture.owner());
        action(
                fixture,
                "CONFIRM_INTERNAL_REVIEW",
                "workspace-review-owner-confirm-" + fixture.token(),
                ownerReview.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "reviewVersion",
                        ownerReview.at(
                                "/data/content/review/version").asLong())),
                200);

        Cookie admin = createHospitalAdmin(fixture);
        JsonNode template = postJson(
                "/api/document-templates/default", admin, null);
        postJson(
                "/api/document-templates/"
                        + template.at("/data/id").asText() + "/publish",
                admin,
                json.writeValueAsString(Map.of(
                        "expectedVersion",
                        template.at("/data/version").asLong())));
        JsonNode style = postJson(
                "/api/citation-styles/default", admin, null);
        postJson(
                "/api/citation-styles/"
                        + style.at("/data/id").asText() + "/publish",
                admin,
                json.writeValueAsString(Map.of(
                        "expectedVersion",
                        style.at("/data/version").asLong())));

        JsonNode exportChoices = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/draft-export",
                fixture.owner());
        String templateKey = exportChoices.at(
                "/data/content/templates/0/templateKey").asText();
        String styleKey = exportChoices.at(
                "/data/content/citationStyles/0/styleKey").asText();
        assertThat(templateKey).startsWith("tpl_");
        assertThat(styleKey).startsWith("style_");
        assertNoInternalDetails(exportChoices.toString());
        action(
                fixture,
                "EXPORT_RESEARCH_DRAFT",
                "workspace-export-" + fixture.token(),
                exportChoices.at("/meta/readModelVersion").asLong(),
                json.writeValueAsString(Map.of(
                        "templateKey", templateKey,
                        "styleKey", styleKey,
                        "confirmReviewedContent", true)),
                200);

        JsonNode completedExport = getJson(
                "/api/research/projects/" + fixture.projectKey()
                        + "/draft-export",
                fixture.owner());
        String downloadUrl = completedExport.at(
                "/data/content/completedExport/downloadUrl").asText();
        assertThat(downloadUrl)
                .startsWith("/api/research/projects/"
                        + fixture.projectKey() + "/exports/exp_");
        assertNoInternalDetails(completedExport.toString());
        MvcResult downloaded = mvc.perform(get(downloadUrl)
                        .cookie(fixture.owner()))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(downloaded.getResponse().getContentType())
                .isEqualTo("application/vnd.openxmlformats-officedocument."
                        + "wordprocessingml.document");
        assertThat(downloaded.getResponse().getHeader("X-Content-SHA256"))
                .isNotBlank();
        assertThat(downloaded.getResponse().getContentAsByteArray())
                .startsWith((byte) 'P', (byte) 'K');

        MvcResult stream = mvc.perform(get(
                        "/api/research/projects/{projectKey}/events",
                        fixture.projectKey())
                        .cookie(fixture.owner())
                        .header("Last-Event-ID", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        String events = stream.getResponse().getContentAsString();
        assertThat(events).contains("event:PROJECT_READ_MODEL_CHANGED");
        assertNoInternalDetails(events);

        MvcResult resync = mvc.perform(get(
                        "/api/research/projects/{projectKey}/events",
                        fixture.projectKey())
                        .cookie(fixture.owner())
                        .header("Last-Event-ID", Long.toString(Long.MAX_VALUE))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(resync.getResponse().getContentAsString())
                .contains("event:PROJECT_RESYNC_REQUIRED");
        assertNoInternalDetails(resync.getResponse().getContentAsString());
    }

    @Test
    void hidesProjectExistenceFromNonMembersAndOtherHospitals() throws Exception {
        Fixture ownerFixture = fixture("V2-BOUNDARY");
        UUID outsiderId = UUID.randomUUID();
        identities.insertUser(user(
                outsiderId, ownerFixture.hospitalId(),
                "outsider-" + ownerFixture.token()));
        Cookie sameHospitalOutsider = login(
                ownerFixture.hospitalCode(),
                "outsider-" + ownerFixture.token());

        Fixture otherHospital = fixture("V2-OTHER");
        assertWorkspaceNotFound(sameHospitalOutsider, ownerFixture.projectKey());
        assertWorkspaceNotFound(otherHospital.owner(), ownerFixture.projectKey());
        assertWorkspaceNotFound(
                ownerFixture.owner(), "prj_2123456789ABCDEFGHJKMNPQRS");
    }

    @Test
    void rejectsMalformedHeadersAndViewerMutations() throws Exception {
        Fixture fixture = fixture("V2-HEADERS");
        UUID viewerId = UUID.randomUUID();
        identities.insertUser(user(
                viewerId, fixture.hospitalId(),
                "viewer-" + fixture.token()));
        Cookie viewer = login(
                fixture.hospitalCode(), "viewer-" + fixture.token());
        mvc.perform(post(
                        "/api/research/projects/{id}/members",
                        fixture.projectId())
                        .cookie(fixture.owner()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "userId", viewerId, "role", "VIEWER"))))
                .andExpect(status().isOk());

        mvc.perform(post(
                        "/api/research/projects/{projectKey}/actions/{action}",
                        fixture.projectKey(), "START_RESEARCH_IDEA")
                        .cookie(fixture.owner()).with(csrf())
                        .header("Idempotency-Key",
                                "workspace-header-" + fixture.token())
                        .header("If-Match", "rmv-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"匿名研究构想\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        mvc.perform(post(
                        "/api/research/projects/{projectKey}/actions/{action}",
                        fixture.projectKey(), "START_RESEARCH_IDEA")
                        .cookie(viewer).with(csrf())
                        .header("Idempotency-Key",
                                "workspace-viewer-" + fixture.token())
                        .header("If-Match", "\"rmv-1\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"idea\":\"匿名研究构想\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code")
                        .value("FORBIDDEN"));

        mvc.perform(get("/api/research/todos")
                        .cookie(fixture.owner())
                        .queryParam("status", "DONE"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));
    }

    @Test
    void persistsAnonymousModelEvaluationAndRequiresTwoDistinctExperts()
            throws Exception {
        Fixture fixture = fixture("V2-EVAL");
        Cookie admin = createHospitalAdmin(fixture);
        mvc.perform(post("/api/research/model-evaluations")
                        .cookie(fixture.owner()).with(csrf())
                        .header("Idempotency-Key",
                                "eval-forbidden-" + UUID.randomUUID()))
                .andExpect(status().isForbidden());

        String startKey = "eval-start-" + UUID.randomUUID();
        JsonNode started = postJson(
                "/api/research/model-evaluations", admin, null, startKey);
        JsonNode evaluation = started.path("data");
        String evaluationKey = evaluation.path("evaluationKey").asText();
        assertThat(evaluationKey).startsWith("eval_");
        assertThat(evaluation.path("dataClassification").asText())
                .isEqualTo("SYNTHETIC_ANONYMOUS");
        assertThat(evaluation.path("status").asText())
                .isEqualTo("WAITING_EXPERT_SCORING");
        assertThat(evaluation.path("caseCount").asInt()).isGreaterThanOrEqualTo(5);
        assertThat(evaluation.path("expertScoringRequired").asBoolean()).isTrue();
        assertThat(started.at("/meta/readModelVersion").asLong())
                .isPositive();
        assertNoInternalDetails(started.toString());
        JsonNode startReplay = postJson(
                "/api/research/model-evaluations", admin, null, startKey);
        assertThat(startReplay.at("/data/evaluationKey").asText())
                .isEqualTo(evaluationKey);

        Cookie medicalExpert = createExpert(fixture, "eval-med-");
        Cookie statisticalExpert = createExpert(fixture, "eval-stat-");
        String medicalScore = json.writeValueAsString(Map.of(
                "responsibility", "MEDICAL_REVIEW",
                "correctnessScore", 4,
                "completenessScore", 4,
                "safetyScore", 5,
                "actionabilityScore", 4,
                "recommendation", "REVISE",
                "comment", "匿名评测结果可用于科研草案迭代，需补充医学边界说明。"));
        String medicalScoreKey = "eval-medical-" + UUID.randomUUID();
        JsonNode afterMedical = postJson(
                "/api/research/model-evaluations/" + evaluationKey
                        + "/expert-scores",
                medicalExpert,
                medicalScore,
                medicalScoreKey);
        assertThat(afterMedical.at("/data/status").asText())
                .isEqualTo("WAITING_EXPERT_SCORING");
        JsonNode medicalReplay = postJson(
                "/api/research/model-evaluations/" + evaluationKey
                        + "/expert-scores",
                medicalExpert,
                medicalScore,
                medicalScoreKey);
        assertThat(medicalReplay.at("/data/expertScores").size())
                .isEqualTo(1);

        mvc.perform(post("/api/research/model-evaluations/"
                                + evaluationKey + "/expert-scores")
                        .cookie(medicalExpert).with(csrf())
                        .header("Idempotency-Key",
                                "eval-independent-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "responsibility", "STATISTICAL_REVIEW",
                                "correctnessScore", 4,
                                "completenessScore", 4,
                                "safetyScore", 5,
                                "actionabilityScore", 4,
                                "recommendation", "REVISE",
                                "comment", "同一专家不得承担第二项独立评分。"))))
                .andExpect(status().isConflict());

        JsonNode completed = postJson(
                "/api/research/model-evaluations/" + evaluationKey
                        + "/expert-scores",
                statisticalExpert,
                json.writeValueAsString(Map.of(
                        "responsibility", "STATISTICAL_REVIEW",
                        "correctnessScore", 4,
                        "completenessScore", 3,
                        "safetyScore", 5,
                        "actionabilityScore", 4,
                        "recommendation", "ACCEPT",
                        "comment", "匿名案例指标可复核，建议保留统计限制和人工评分记录。")));
        assertThat(completed.at("/data/status").asText())
                .isEqualTo("COMPLETED");
        assertThat(completed.at("/data/expertScores").size()).isEqualTo(2);
        assertThat(completed.at("/data/expertScoringRequired").asBoolean())
                .isFalse();
        assertNoInternalDetails(completed.toString());
    }

    private Fixture fixture(String prefix) throws Exception {
        String token = UUID.randomUUID().toString().replace("-", "")
                .substring(0, 12);
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        String hospitalCode = prefix + "-" + token.toUpperCase();
        String username = "owner-" + token;
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalId, hospitalCode, prefix + "医院", Instant.now()));
        identities.insertUser(user(ownerId, hospitalId, username));
        Cookie owner = login(hospitalCode, username);
        JsonNode project = createProject(owner, prefix, prefix + "匿名课题", token);
        return new Fixture(
                token, hospitalId, hospitalCode, ownerId,
                UUID.fromString(project.path("id").asText()),
                project.path("projectKey").asText(), owner);
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username) {
        return user(id, hospitalId, username, Set.of(Role.DOCTOR));
    }

    private IdentityRepository.UserData user(
            UUID id,
            UUID hospitalId,
            String username,
            Set<Role> roles) {
        return new IdentityRepository.UserData(
                id, hospitalId, username, passwordEncoder.encode(PASSWORD),
                roles, true, false, 0, null);
    }

    private Cookie login(String hospitalCode, String username) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "hospitalCode", hospitalCode,
                                "username", username,
                                "password", PASSWORD))))
                .andExpect(status().isOk())
                .andReturn();
        Cookie cookie = result.getResponse()
                .getCookie(SessionAuthenticationFilter.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private Cookie createHospitalAdmin(Fixture fixture) throws Exception {
        String username = "admin-" + fixture.token();
        identities.insertUser(user(
                UUID.randomUUID(),
                fixture.hospitalId(),
                username,
                Set.of(Role.HOSPITAL_ADMIN)));
        return login(fixture.hospitalCode(), username);
    }

    private Cookie createExpert(Fixture fixture, String prefix)
            throws Exception {
        String username = prefix + fixture.token();
        identities.insertUser(user(
                UUID.randomUUID(),
                fixture.hospitalId(),
                username,
                Set.of(Role.EXPERT)));
        return login(fixture.hospitalCode(), username);
    }

    private JsonNode postJson(
            String path,
            Cookie actor,
            String body) throws Exception {
        return postJson(
                path, actor, body, "http-test-" + UUID.randomUUID());
    }

    private JsonNode postJson(
            String path,
            Cookie actor,
            String body,
            String idempotencyKey) throws Exception {
        var request = post(path)
                .cookie(actor)
                .with(csrf())
                .header("Idempotency-Key", idempotencyKey);
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(body);
        }
        String response = mvc.perform(request)
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(response);
    }

    private JsonNode createProject(
            Cookie owner, String code, String name, String token)
            throws Exception {
        String body = mvc.perform(post("/api/research/projects")
                        .cookie(owner).with(csrf())
                        .header("Idempotency-Key", "workspace-project-" + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(
                                Map.of("code", code, "name", name))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body).path("data");
    }

    private JsonNode getJson(String path, Cookie cookie) throws Exception {
        String body = mvc.perform(get(path).cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return json.readTree(body);
    }

    private JsonNode pollUntilAction(
            Fixture fixture, String actionCode, int maxPolls)
            throws Exception {
        for (int index = 0; index < maxPolls; index++) {
            worker.poll();
            JsonNode summary = getJson(
                    "/api/research/projects/" + fixture.projectKey()
                            + "/workspace-summary",
                    fixture.owner());
            if (actionCode.equals(
                    summary.at("/data/nextAction/code").asText())) {
                return summary;
            }
        }
        throw new AssertionError(
                "未在预期轮询次数内到达动作 " + actionCode);
    }

    private String action(
            Fixture fixture,
            String action,
            String idempotencyKey,
            long readModelVersion,
            String body,
            int expectedStatus) throws Exception {
        return mvc.perform(post(
                        "/api/research/projects/{projectKey}/actions/{action}",
                        fixture.projectKey(), action)
                        .cookie(fixture.owner()).with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header("If-Match",
                                "\"rmv-" + readModelVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private String action(
            String projectKey,
            Cookie actor,
            String action,
            String idempotencyKey,
            long readModelVersion,
            String body,
            int expectedStatus) throws Exception {
        return mvc.perform(post(
                        "/api/research/projects/{projectKey}/actions/{action}",
                        projectKey, action)
                        .cookie(actor).with(csrf())
                        .header("Idempotency-Key", idempotencyKey)
                        .header(
                                "If-Match",
                                "\"rmv-" + readModelVersion + "\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().is(expectedStatus))
                .andReturn().getResponse().getContentAsString();
    }

    private void addMember(
            Fixture fixture,
            UUID userId,
            String role) throws Exception {
        mvc.perform(post(
                        "/api/research/projects/{id}/members",
                        fixture.projectId())
                        .cookie(fixture.owner()).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "userId", userId,
                                "role", role))))
                .andExpect(status().isOk());
    }

    private void assertWorkspaceNotFound(Cookie actor, String projectKey)
            throws Exception {
        mvc.perform(get(
                        "/api/research/projects/{projectKey}/workspace-summary",
                        projectKey)
                        .cookie(actor))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("PROJECT_NOT_FOUND"));
    }

    private void assertNoInternalDetails(String serialized) {
        assertThat(serialized)
                .doesNotContain("STEP_")
                .doesNotContain("outputJson")
                .doesNotContain("\"taskId\"")
                .doesNotContain("\"hospitalId\"")
                .doesNotContain("\"candidateSetId\"");
        assertThat(UUID_PATTERN.matcher(serialized).find()).isFalse();
    }

    private record Fixture(
            String token,
            UUID hospitalId,
            String hospitalCode,
            UUID ownerId,
            UUID projectId,
            String projectKey,
            Cookie owner) {}
}
