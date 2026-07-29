package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.auth.SessionAuthenticationFilter;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest(properties = {
        "medical.security.secure-cookie=false",
        "medical.agent.worker-initial-delay=1h",
        "medical.agent.sse-timeout=100ms"
})
@AutoConfigureMockMvc
class AgentWorkflowHttpTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityRepository identities;
    @Autowired PasswordEncoder encoder;
    @Autowired AgentWorkflowWorker worker;

    @Test
    void blocksCrossHospitalProjectAndTaskIdentifiersAtHttpBoundary() throws Exception {
        UUID hospitalA = UUID.randomUUID();
        UUID hospitalB = UUID.randomUUID();
        UUID userA = UUID.randomUUID();
        UUID userB = UUID.randomUUID();
        String codeA = "BOUND-A-" + hospitalA.toString().substring(0, 8);
        String codeB = "BOUND-B-" + hospitalB.toString().substring(0, 8);
        String password = "InitialPass123";
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalA, codeA, "边界测试医院A", Instant.now()));
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalB, codeB, "边界测试医院B", Instant.now()));
        identities.insertUser(user(userA, hospitalA, "boundary-a-" + userA, password));
        identities.insertUser(user(userB, hospitalB, "boundary-b-" + userB, password));
        Cookie cookieA = login(codeA, "boundary-a-" + userA, password);
        Cookie cookieB = login(codeB, "boundary-b-" + userB, password);

        String projectBody = mvc.perform(post("/api/research/projects")
                        .cookie(cookieA).with(csrf())
                        .header("Idempotency-Key", "boundary-project-" + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "BOUNDARY-A", "name", "医院A隔离课题"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(
                json.readTree(projectBody).at("/data/id").asText());
        String taskBody = mvc.perform(post("/api/agent/tasks")
                        .cookie(cookieA).with(csrf())
                        .header("Idempotency-Key", "boundary-task-" + userA)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "projectId", projectId,
                                "idea", "匿名医院A研究想法"))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(json.readTree(taskBody).at("/data/id").asText());

        mvc.perform(get("/api/research/projects/{id}", projectId).cookie(cookieB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(cookieB))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/tasks")
                        .queryParam("projectId", projectId.toString())
                        .cookie(cookieB))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/agent/tasks/{id}/cancel", taskId)
                        .cookie(cookieB).with(csrf()))
                .andExpect(status().isNotFound());
        mvc.perform(post("/api/research/projects/{id}/members", projectId)
                        .cookie(cookieB).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "userId", userB, "role", "OWNER"))))
                .andExpect(status().isNotFound());
    }

    @Test
    void enforcesEditorBoundariesAndReplaysSseForViewers() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID outsiderId = UUID.randomUUID();
        UUID expertId = UUID.randomUUID();
        String hospitalCode = "HTTP-" + hospitalId.toString().substring(0, 8);
        String password = "InitialPass123";
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalId, hospitalCode, "HTTP测试医院", Instant.now()));
        identities.insertUser(user(ownerId, hospitalId, "owner-" + ownerId, password));
        identities.insertUser(user(viewerId, hospitalId, "viewer-" + viewerId, password));
        identities.insertUser(user(outsiderId, hospitalId, "outsider-" + outsiderId, password));
        identities.insertUser(user(
                expertId, hospitalId, "expert-" + expertId, password, Set.of(Role.EXPERT)));

        Cookie owner = login(hospitalCode, "owner-" + ownerId, password);
        Cookie viewer = login(hospitalCode, "viewer-" + viewerId, password);
        Cookie outsider = login(hospitalCode, "outsider-" + outsiderId, password);
        Cookie expert = login(hospitalCode, "expert-" + expertId, password);

        String projectBody = mvc.perform(post("/api/research/projects")
                        .cookie(owner).with(csrf())
                        .header("Idempotency-Key", "project-" + ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "code", "HTTP-AGENT", "name", "Agent HTTP 权限测试"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID projectId = UUID.fromString(json.readTree(projectBody).at("/data/id").asText());
        mvc.perform(post("/api/research/projects/{id}/members", projectId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "userId", viewerId, "role", "VIEWER"))))
                .andExpect(status().isOk());
        mvc.perform(post("/api/research/projects/{id}/members", projectId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "userId", expertId, "role", "VIEWER"))))
                .andExpect(status().isOk());

        String taskBody = mvc.perform(post("/api/agent/tasks")
                        .cookie(owner).with(csrf())
                        .header("Idempotency-Key", "task-" + ownerId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "projectId", projectId,
                                "idea", "匿名测试：研究2型糖尿病患者用药与肾功能变化"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        UUID taskId = UUID.fromString(json.readTree(taskBody).at("/data/id").asText());
        worker.poll();

        String waitingBody = mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStep").value("STEP_03_ASK_CLARIFICATION"))
                .andReturn().getResponse().getContentAsString();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(outsider))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/tasks/{id}/clarifications", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));

        MvcResult stream = mvc.perform(get("/api/agent/tasks/{id}/events", taskId)
                        .cookie(viewer)
                        .header("Last-Event-ID", "0")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(stream.getResponse().getContentAsString()).contains("event:TASK_CREATED");
        mvc.perform(get("/api/agent/tasks/{id}/events", taskId)
                        .cookie(viewer).header("Last-Event-ID", "invalid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_REQUEST"));

        Map<String, String> answers = answers(
                json.readTree(waitingBody).at("/data/output/clarificationQuestions"));
        String payload = json.writeValueAsString(Map.of("answers", answers));
        mvc.perform(post("/api/agent/tasks/{id}/clarifications", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/clarifications", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(payload))
                .andExpect(status().isOk());
        mvc.perform(get("/api/agent/tasks/{id}/clarifications", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].roundNo").value(1))
                .andExpect(jsonPath("$.data[0].answers").isMap());

        worker.poll();
        mvc.perform(post("/api/agent/tasks/{id}/confirm-direction", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"directionId\":\"DIR-02\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/confirm-direction", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"directionId\":\"DIR-02\"}"))
                .andExpect(status().isOk());
        worker.poll();

        String strategyBody = mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_07_BUILD_SEARCH_STRATEGY"))
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.output.searchStrategy.queryVersion")
                        .value("pubmed-query/v1"))
                .andReturn().getResponse().getContentAsString();
        String query = json.readTree(strategyBody)
                .at("/data/output/searchStrategy/pubmedQuery").asText();
        String strategyPayload = json.writeValueAsString(Map.of(
                "pubmedQuery", query + "\nNOT animals[MeSH Terms]"));
        mvc.perform(post("/api/agent/tasks/{id}/confirm-search-strategy", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(strategyPayload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/confirm-search-strategy", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(strategyPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep").value("STEP_08_SEARCH_PUBMED"))
                .andExpect(jsonPath("$.data.output.searchStrategy.confirmationStatus")
                        .value("CONFIRMED"));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_09_SEARCH_CLINICAL_TRIALS"))
                .andExpect(jsonPath("$.data.output.pubmedSearch.records.length()").value(2));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_10_VALIDATE_LITERATURE"))
                .andExpect(jsonPath(
                        "$.data.output.clinicalTrialsSearch.records.length()").value(2));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_11_ANALYZE_SIMILAR_RESEARCH"))
                .andExpect(jsonPath(
                        "$.data.output.literatureValidation.citations.length()").value(2));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN"))
                .andExpect(jsonPath("$.data.output.pubmedSearch.records.length()").value(2))
                .andExpect(jsonPath("$.data.output.pubmedSearch.records[0].verified").value(true))
                .andExpect(jsonPath("$.data.output.clinicalTrialsSearch.records.length()").value(2))
                .andExpect(jsonPath("$.data.output.clinicalTrialsSearch.records[0].verified")
                        .value(true))
                .andExpect(jsonPath("$.data.output.literatureValidation.citations.length()")
                        .value(2))
                .andExpect(jsonPath("$.data.output.literatureValidation.evidenceLinks.length()")
                        .value(2))
                .andExpect(jsonPath("$.data.output.similarResearchAnalysis.similarResearch.length()")
                        .value(4))
                .andExpect(jsonPath("$.data.output.similarResearchAnalysis.algorithmVersion")
                        .value("deterministic-peco-overlap/v1"));
        worker.poll();
        String designBody = mvc.perform(
                        get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN"))
                .andExpect(jsonPath(
                        "$.data.output.observationalDesignRecommendation.alternatives.length()")
                        .value(3))
                .andExpect(jsonPath(
                        "$.data.output.observationalDesignRecommendation.confirmationStatus")
                        .value("PENDING_CONFIRMATION"))
                .andReturn().getResponse().getContentAsString();
        JsonNode recommendation = json.readTree(designBody)
                .at("/data/output/observationalDesignRecommendation");
        String designPayload = json.writeValueAsString(Map.of(
                "studyType", recommendation.path("recommendedStudyType").asText(),
                "primaryOutcome", recommendation.path("primaryOutcomeCandidate").asText(),
                "authorizeProtocolGeneration", true));
        mvc.perform(post("/api/agent/tasks/{id}/confirm-observational-design", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(designPayload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/confirm-observational-design", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON).content(designPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_13_GENERATE_PROTOCOL_SECTIONS"))
                .andExpect(jsonPath(
                        "$.data.output.observationalDesignRecommendation.confirmationStatus")
                        .value("CONFIRMED"))
                .andExpect(jsonPath(
                        "$.data.output.observationalDesignRecommendation.protocolGenerationAuthorized")
                        .value(true));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_14_GENERATE_STATISTICAL_DRAFT"))
                .andExpect(jsonPath("$.data.output.protocolDraft.schemaVersion")
                        .value("research-protocol-draft/v1"))
                .andExpect(jsonPath("$.data.output.protocolDraft.sections.length()")
                        .value(18));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_15_VALIDATE_CLAIMS_AND_CITATIONS"))
                .andExpect(jsonPath(
                        "$.data.output.statisticalAnalysisDraft.schemaVersion")
                        .value("statistical-analysis-draft/v1"));
        worker.poll();
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("QUEUED"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_16_CHECK_STROBE_COMPLETENESS"))
                .andExpect(jsonPath(
                        "$.data.output.claimCitationValidation.schemaVersion")
                        .value("claim-citation-validation-result/v1"));
        worker.poll();
        String reviewWaitingBody = mvc.perform(
                        get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_17_WAIT_EXPERT_REVIEW"))
                .andExpect(jsonPath("$.data.output.protocolDraft.schemaVersion")
                        .value("research-protocol-draft/v1"))
                .andExpect(jsonPath("$.data.output.protocolDraft.sections.length()")
                        .value(18))
                .andExpect(jsonPath("$.data.output.protocolDraft.sections[17].sectionCode")
                        .value("REFERENCES"))
                .andExpect(jsonPath(
                        "$.data.output.protocolDraft.sections[12].versionNo").value(2))
                .andExpect(jsonPath(
                        "$.data.output.statisticalAnalysisDraft.schemaVersion")
                        .value("statistical-analysis-draft/v1"))
                .andExpect(jsonPath(
                        "$.data.output.statisticalAnalysisDraft.sampleSizeParameters.length()")
                        .value(8))
                .andExpect(jsonPath(
                        "$.data.output.statisticalAnalysisDraft.sampleSizeParameters[0].valueStatus")
                        .value("MISSING_NEEDS_INPUT"))
                .andExpect(jsonPath(
                        "$.data.output.statisticalAnalysisDraft.sampleSizeParameters[0].value")
                        .value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath(
                        "$.data.output.claimCitationValidation.schemaVersion")
                        .value("claim-citation-validation-result/v1"))
                .andExpect(jsonPath(
                        "$.data.output.claimCitationValidation.claims.length()")
                        .value(org.hamcrest.Matchers.greaterThan(0)))
                .andExpect(jsonPath(
                        "$.data.output.claimCitationValidation.claims[0].expertConfirmationStatus")
                        .value("PENDING_REVIEW"))
                .andExpect(jsonPath(
                        "$.data.output.strobeCompletenessCheck.schemaVersion")
                        .value("strobe-completeness-check-result/v1"))
                .andExpect(jsonPath(
                        "$.data.output.strobeCompletenessCheck.totalItemCount").value(22))
                .andExpect(jsonPath(
                        "$.data.output.strobeCompletenessCheck.items.length()").value(22))
                .andExpect(jsonPath(
                        "$.data.output.strobeCompletenessCheck.automaticPrecheckDisclaimer")
                        .value(org.hamcrest.Matchers.containsString("不是研究质量评分工具")))
                .andExpect(jsonPath("$.data.output.expertReview.status")
                        .value("WAITING_EXPERT_REVIEW"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(get("/api/agent/tasks/{id}/expert-review", taskId)
                        .cookie(outsider))
                .andExpect(status().isNotFound());
        mvc.perform(get("/api/agent/tasks/{id}/expert-review", taskId)
                        .cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.history[0].actionType")
                        .value("REVIEW_OPENED"));

        JsonNode reviewOutput = json.readTree(reviewWaitingBody).at("/data/output");
        String strobeItemId = reviewOutput
                .at("/strobeCompletenessCheck/items/9/itemResultId").asText();
        String commentPayload = json.writeValueAsString(Map.of(
                "strobeItemResultId", strobeItemId,
                "commentType", "STATISTICAL",
                "content", "请补充样本量参数来源和统计学确认依据。"));
        mvc.perform(post("/api/agent/tasks/{id}/expert-review/comments", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentPayload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/expert-review/comments", taskId)
                        .cookie(expert).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(commentPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.comments[0].strobeItemResultId")
                        .value(strobeItemId))
                .andExpect(jsonPath("$.data.comments[0].commentType")
                        .value("STATISTICAL"));

        String decisionPayload = json.writeValueAsString(Map.of(
                "decision", "APPROVE",
                "summary", "当前版本可进入课题负责人确认，统计参数仍需在正式研究前落实。",
                "expectedVersion", 0));
        mvc.perform(post("/api/agent/tasks/{id}/expert-review/decision", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionPayload))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/expert-review/decision", taskId)
                        .cookie(expert).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decisionPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXPERT_APPROVED"))
                .andExpect(jsonPath("$.data.version").value(1));

        mvc.perform(post("/api/agent/tasks/{id}/expert-review/owner-confirmation", taskId)
                        .cookie(viewer).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isForbidden());
        mvc.perform(post("/api/agent/tasks/{id}/expert-review/owner-confirmation", taskId)
                        .cookie(owner).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"expectedVersion\":1}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("APPROVED"))
                .andExpect(jsonPath("$.data.sectionsLocked").value(true))
                .andExpect(jsonPath("$.data.history[3].actionType")
                        .value("OWNER_CONFIRMED"));
        mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(viewer))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("WAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.currentStep")
                        .value("STEP_18_EXPORT_DOCUMENT"));
    }

    private Cookie login(String hospitalCode, String username, String password) throws Exception {
        MvcResult result = mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(Map.of(
                                "hospitalCode", hospitalCode,
                                "username", username,
                                "password", password))))
                .andExpect(status().isOk()).andReturn();
        Cookie cookie = result.getResponse().getCookie(SessionAuthenticationFilter.COOKIE_NAME);
        assertThat(cookie).isNotNull();
        return cookie;
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username, String password) {
        return user(id, hospitalId, username, password, Set.of(Role.DOCTOR));
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username, String password,
            Set<Role> roles) {
        return new IdentityRepository.UserData(
                id, hospitalId, username, encoder.encode(password),
                roles, true, false, 0, null);
    }

    private Map<String, String> answers(JsonNode questions) {
        Map<String, String> answers = new LinkedHashMap<>();
        questions.forEach(question -> answers.put(question.asText(), "匿名测试答案"));
        return answers;
    }
}
