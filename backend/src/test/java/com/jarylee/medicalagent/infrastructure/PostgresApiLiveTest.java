package com.jarylee.medicalagent.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.auth.IdentityRepository;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.file.ObjectStorage;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.mock.web.MockMultipartFile;
import org.apache.poi.xwpf.usermodel.XWPFDocument;

import java.io.ByteArrayOutputStream;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "medical.security.secure-cookie=false")
@AutoConfigureMockMvc
@ActiveProfiles("postgres")
@EnabledIfSystemProperty(named = "livePostgresApi", matches = "true")
class PostgresApiLiveTest {
    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;
    @Autowired IdentityRepository identities;
    @Autowired PasswordEncoder encoder;
    @Autowired JdbcClient jdbc;
    @Autowired ObjectStorage objectStorage;
    @Value("${medical.file-scan.mode:basic}") String fileScanMode;

    @Test
    void enforcesMembershipThroughRealSpringSecurityAndPostgresRepositories() throws Exception {
        UUID hospitalId = UUID.randomUUID();
        UUID ownerId = UUID.randomUUID();
        UUID viewerId = UUID.randomUUID();
        UUID expertId = UUID.randomUUID();
        UUID medicalExpertId = UUID.randomUUID();
        String code = "LIVE-API-" + hospitalId;
        String password = "InitialPass123";
        identities.insertHospital(new IdentityRepository.HospitalData(
                hospitalId, code, "Live API Hospital", Instant.now()));
        identities.insertUser(user(ownerId, hospitalId, "owner-" + ownerId, password,
                Set.of(Role.DOCTOR, Role.HOSPITAL_ADMIN)));
        identities.insertUser(user(viewerId, hospitalId, "viewer-" + viewerId, password));
        identities.insertUser(user(
                expertId, hospitalId, "expert-" + expertId, password,
                Set.of(Role.EXPERT)));
        identities.insertUser(user(
                medicalExpertId, hospitalId, "medical-expert-" + medicalExpertId,
                password, Set.of(Role.EXPERT)));

        UUID projectId = null;
        UUID taskId = null;
        String uploadedObjectKey = null;
        String searchRawObjectKey = null;
        String trialRawObjectKey = null;
        String validationRawObjectKey = null;
        String templateObjectKey = null;
        String exportObjectKey = null;
        try {
            Cookie ownerCookie = login(code, "owner-" + ownerId, password);
            String createResponse = mvc.perform(post("/api/research/projects")
                            .cookie(ownerCookie).with(csrf())
                            .header("Idempotency-Key", "api-" + ownerId)
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "code", "API-001", "name", "API membership project"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            projectId = UUID.fromString(json.readTree(createResponse).at("/data/id").asText());

            Cookie viewerCookie = login(code, "viewer-" + viewerId, password);
            Cookie expertCookie = login(code, "expert-" + expertId, password);
            Cookie medicalExpertCookie = login(
                    code, "medical-expert-" + medicalExpertId, password);
            String before = mvc.perform(get("/api/research/projects").cookie(viewerCookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(before).at("/data").size()).isZero();

            mvc.perform(post("/api/research/projects/{id}/members", projectId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "userId", viewerId.toString(), "role", "VIEWER"))))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/research/projects/{id}/members", projectId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "userId", medicalExpertId.toString(),
                                    "role", "VIEWER"))))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/research/projects/{id}/members", projectId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "userId", expertId.toString(), "role", "VIEWER"))))
                    .andExpect(status().isOk());

            String after = mvc.perform(get("/api/research/projects").cookie(viewerCookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(after).at("/data").size()).isEqualTo(1);

            String taskResponse = mvc.perform(post("/api/agent/tasks")
                            .cookie(ownerCookie).with(csrf())
                            .header("Idempotency-Key", "agent-" + ownerId)
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "projectId", projectId,
                                    "idea", "我想研究2型糖尿病患者使用SGLT2抑制剂后肾功能的变化"))))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            taskId = UUID.fromString(json.readTree(taskResponse).at("/data/id").asText());
            String clarificationTask = waitForTaskStep(
                    taskId, ownerCookie, "STEP_03_ASK_CLARIFICATION");
            Map<String, String> clarificationAnswers = new java.util.LinkedHashMap<>();
            json.readTree(clarificationTask).at("/data/output/clarificationQuestions")
                    .forEach(question -> clarificationAnswers.put(
                            question.asText(), answerFor(question.asText())));
            mvc.perform(post("/api/agent/tasks/{id}/clarifications", taskId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("answers", clarificationAnswers))))
                    .andExpect(status().isOk());
            String firstDirections = waitForTaskStep(
                    taskId, ownerCookie, "STEP_05_CONFIRM_DIRECTION");
            String revisedQuestion = json.readTree(firstDirections)
                    .at("/data/output/clarificationQuestions/0").asText();
            clarificationAnswers.put(revisedQuestion, "第二轮修订后的匿名答案");
            mvc.perform(post("/api/agent/tasks/{id}/clarifications", taskId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of("answers", clarificationAnswers))))
                    .andExpect(status().isOk());
            String secondDirections = waitForTaskStep(
                    taskId, ownerCookie, "STEP_05_CONFIRM_DIRECTION");
            Integer clarificationRounds = jdbc.sql("""
                            select count(*) from ai_agent_clarification_round
                            where hospital_id=:hospitalId and task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query(Integer.class).single();
            assertThat(clarificationRounds).isEqualTo(2);
            String secondRoundAnswer = jdbc.sql("""
                            select answers_json ->> :question
                            from ai_agent_clarification_round
                            where task_id=:taskId and round_no=2
                            """).param("question", revisedQuestion).param("taskId", taskId)
                    .query(String.class).single();
            assertThat(secondRoundAnswer).isEqualTo("第二轮修订后的匿名答案");
            var secondDirectionsOutput = json.readTree(secondDirections).at("/data/output");
            mvc.perform(post("/api/agent/tasks/{id}/confirm-direction", taskId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "directionId", "DIR-02",
                                    "candidateSetId",
                                    secondDirectionsOutput.path("candidateSetId").asText(),
                                    "candidateSetHash",
                                    secondDirectionsOutput.path("candidateSetHash").asText()))))
                    .andExpect(status().isOk());
            String strategyTask = waitForTaskStep(
                    taskId, ownerCookie, "STEP_07_BUILD_SEARCH_STRATEGY");
            String generatedQuery = json.readTree(strategyTask)
                    .at("/data/output/searchStrategy/pubmedQuery").asText();
            mvc.perform(post("/api/agent/tasks/{id}/confirm-search-strategy", taskId)
                            .cookie(viewerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "pubmedQuery", generatedQuery))))
                    .andExpect(status().isForbidden());
            mvc.perform(post("/api/agent/tasks/{id}/confirm-search-strategy", taskId)
                            .cookie(ownerCookie).with(csrf()).contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "pubmedQuery", generatedQuery + "\nNOT animals[MeSH Terms]"))))
                    .andExpect(status().isOk());
            waitForTaskStep(
                    taskId, ownerCookie, "STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN");
            String designTask = waitForTaskStatus(
                    taskId, ownerCookie, "WAITING_CONFIRMATION");
            var designRecommendation = json.readTree(designTask)
                    .at("/data/output/observationalDesignRecommendation");
            String designConfirmation = json.writeValueAsString(Map.of(
                    "studyType", designRecommendation.path("recommendedStudyType").asText(),
                    "primaryOutcome",
                    designRecommendation.path("primaryOutcomeCandidate").asText(),
                    "authorizeProtocolGeneration", true));
            mvc.perform(post(
                            "/api/agent/tasks/{id}/confirm-observational-design", taskId)
                            .cookie(viewerCookie).with(csrf())
                            .contentType("application/json")
                            .content(designConfirmation))
                    .andExpect(status().isForbidden());
            mvc.perform(post(
                            "/api/agent/tasks/{id}/confirm-observational-design", taskId)
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content(designConfirmation))
                    .andExpect(status().isOk())
                    .andReturn();
            String completedTask = waitForTaskStatus(
                    taskId, ownerCookie, "WAITING_CONFIRMATION");
            assertThat(json.readTree(completedTask).at("/data/currentStep").asText())
                    .isEqualTo("STEP_17_WAIT_EXPERT_REVIEW");
            assertThat(json.readTree(completedTask).at("/data/output/peco/schemaVersion").asText())
                    .isEqualTo("peco/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/designAssessment/readyForDraft").asBoolean()).isTrue();
            assertThat(json.readTree(completedTask)
                    .at("/data/output/searchStrategy/confirmationStatus").asText())
                    .isEqualTo("CONFIRMED");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/pubmedSearch/schemaVersion").asText())
                    .isEqualTo("pubmed-search-result/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/pubmedSearch/records").size()).isEqualTo(2);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/clinicalTrialsSearch/schemaVersion").asText())
                    .isEqualTo("clinicaltrials-search-result/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/clinicalTrialsSearch/records").size()).isEqualTo(2);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/literatureValidation/schemaVersion").asText())
                    .isEqualTo("literature-validation-result/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/literatureValidation/citations").size()).isEqualTo(2);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/literatureValidation/evidenceLinks").size()).isEqualTo(2);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/similarResearchAnalysis/schemaVersion").asText())
                    .isEqualTo("similar-research-analysis-result/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/similarResearchAnalysis/similarResearch").size())
                    .isEqualTo(4);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/similarResearchAnalysis/conclusion").asText())
                    .contains("不代表完成了全部数据库和灰色文献检索");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/observationalDesignRecommendation/confirmationStatus")
                    .asText()).isEqualTo("CONFIRMED");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/observationalDesignRecommendation/"
                            + "protocolGenerationAuthorized")
                    .asBoolean()).isTrue();
            assertThat(json.readTree(completedTask)
                    .at("/data/output/protocolDraft/schemaVersion").asText())
                    .isEqualTo("research-protocol-draft/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/protocolDraft/sections").size())
                    .isEqualTo(18);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/protocolDraft/sections/17/content").asText())
                    .contains("PMID:");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/protocolDraft/sections/12/versionNo").asInt())
                    .isEqualTo(2);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/statisticalAnalysisDraft/schemaVersion").asText())
                    .isEqualTo("statistical-analysis-draft/v1");
            assertThat(json.readTree(completedTask)
                    .at("/data/output/statisticalAnalysisDraft/sampleSizeParameters").size())
                    .isEqualTo(8);
            assertThat(json.readTree(completedTask)
                    .at("/data/output/statisticalAnalysisDraft/sampleSizeParameters/0/value")
                    .isNull()).isTrue();
            var claimValidation = json.readTree(completedTask)
                    .at("/data/output/claimCitationValidation");
            assertThat(claimValidation.path("schemaVersion").asText())
                    .isEqualTo("claim-citation-validation-result/v1");
            assertThat(claimValidation.path("claimCount").asInt()).isPositive();
            assertThat(claimValidation.path("citationLinkCount").asInt()).isPositive();
            assertThat(claimValidation.path("claims").toString())
                    .doesNotContain(
                            "\"supportStatus\":\"SUPPORTED\"",
                            "\"evidenceScope\":\"FULL_TEXT\"");
            var strobeCheck = json.readTree(completedTask)
                    .at("/data/output/strobeCompletenessCheck");
            assertThat(strobeCheck.path("schemaVersion").asText())
                    .isEqualTo("strobe-completeness-check-result/v1");
            assertThat(strobeCheck.path("totalItemCount").asInt()).isEqualTo(22);
            assertThat(strobeCheck.path("items").size()).isEqualTo(22);
            assertThat(strobeCheck.path("automaticPrecheckDisclaimer").asText())
                    .contains("自动预检查", "不是研究质量评分工具");
            assertThat(strobeCheck.toString()).doesNotContain("\"score\"");
            String strobeItemId = strobeCheck.path("items").get(9)
                    .path("itemResultId").asText();
            String reviewResponse = mvc.perform(get(
                            "/api/agent/tasks/{id}/expert-review", taskId)
                            .cookie(expertCookie))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(json.readTree(reviewResponse).at("/data/status").asText())
                    .isEqualTo("WAITING_EXPERT_REVIEW");
            mvc.perform(post("/api/agent/tasks/{id}/expert-review/comments", taskId)
                            .cookie(expertCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "strobeItemResultId", strobeItemId,
                                    "commentType", "STATISTICAL",
                                    "responsibility", "STATISTICAL_REVIEW",
                                    "content", "请核对样本量参数来源。"))))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/agent/tasks/{id}/expert-review/decision", taskId)
                            .cookie(expertCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "responsibility", "STATISTICAL_REVIEW",
                                    "decision", "APPROVE",
                                    "summary", "当前方案可进入负责人确认。",
                                    "expectedVersion", 0))))
                    .andExpect(status().isOk());
            mvc.perform(post("/api/agent/tasks/{id}/expert-review/decision", taskId)
                            .cookie(medicalExpertCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "responsibility", "MEDICAL_REVIEW",
                                    "decision", "APPROVE",
                                    "summary", "医学研究设计可进入负责人确认。",
                                    "expectedVersion", 1))))
                    .andExpect(status().isOk());
            mvc.perform(post(
                            "/api/agent/tasks/{id}/expert-review/owner-confirmation", taskId)
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content("{\"expectedVersion\":2}"))
                    .andExpect(status().isOk());
            String exportWaiting = waitForTaskStep(
                    taskId, ownerCookie, "STEP_18_EXPORT_DOCUMENT");
            assertThat(json.readTree(exportWaiting).at("/data/status").asText())
                    .isEqualTo("WAITING_CONFIRMATION");
            String templateResponse = mvc.perform(
                            post("/api/document-templates/default")
                                    .cookie(ownerCookie).with(csrf()))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            UUID templateVersionId = UUID.fromString(
                    json.readTree(templateResponse).at("/data/id").asText());
            long templateVersion = json.readTree(templateResponse)
                    .at("/data/version").asLong();
            byte[] previewDocx = mvc.perform(post(
                            "/api/document-templates/{id}/preview",
                            templateVersionId)
                            .cookie(ownerCookie).with(csrf()))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsByteArray();
            try (var previewDocument = new XWPFDocument(
                    new java.io.ByteArrayInputStream(previewDocx))) {
                String previewText = previewDocument.getParagraphs().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right)
                        + previewDocument.getTables().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right);
                assertThat(previewText)
                        .contains("模板试生成")
                        .doesNotContain("${");
            }
            String publishedTemplate = mvc.perform(post(
                            "/api/document-templates/{id}/publish", templateVersionId)
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "expectedVersion", templateVersion))))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(json.readTree(publishedTemplate).at("/data/status").asText())
                    .isEqualTo("PUBLISHED");
            String styleResponse = mvc.perform(post("/api/citation-styles")
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "styleCode", "HOSPITAL_GBT",
                                    "styleName", "医院 GB/T 7714 数字格式",
                                    "layout", "GB_T_7714",
                                    "authorLimit", 3,
                                    "etAlText", "等",
                                    "includeDoi", true,
                                    "includeEvidenceScope", true,
                                    "evidenceScopeLabel", "摘要级证据"))))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            UUID citationStyleVersionId = UUID.fromString(
                    json.readTree(styleResponse).at("/data/id").asText());
            long styleVersion = json.readTree(styleResponse)
                    .at("/data/version").asLong();
            String publishedStyle = mvc.perform(post(
                            "/api/citation-styles/{id}/publish",
                            citationStyleVersionId)
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "expectedVersion", styleVersion))))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(json.readTree(publishedStyle).at("/data/status").asText())
                    .isEqualTo("PUBLISHED");
            String exportResponse = mvc.perform(post(
                            "/api/agent/tasks/{id}/document-export", taskId)
                            .cookie(ownerCookie).with(csrf())
                            .contentType("application/json")
                            .content(json.writeValueAsString(Map.of(
                                    "templateVersionId", templateVersionId,
                                    "citationStyleVersionId",
                                    citationStyleVersionId,
                                    "confirmReviewedContent", true))))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            UUID exportId = UUID.fromString(
                    json.readTree(exportResponse).at("/data/id").asText());
            assertThat(json.readTree(exportResponse).at("/data/contentSha256").asText())
                    .matches("[0-9a-f]{64}");
            assertThat(json.readTree(exportResponse).at("/data/citationCount").asInt())
                    .isPositive();
            assertThat(json.readTree(exportResponse)
                    .at("/data/citationStyleVersionId").asText())
                    .isEqualTo(citationStyleVersionId.toString());
            assertThat(json.readTree(exportResponse)
                    .at("/data/citationStyleVersion").asText())
                    .isEqualTo("HOSPITAL_GBT/v1");
            byte[] exportedDocx = mvc.perform(get(
                            "/api/document-exports/{id}/download", exportId)
                            .cookie(ownerCookie))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsByteArray();
            try (var document = new XWPFDocument(
                    new java.io.ByteArrayInputStream(exportedDocx))) {
                String exportedText = document.getParagraphs().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right)
                        + document.getTables().stream()
                        .map(value -> value.getText())
                        .reduce("", (left, right) -> left + "\n" + right);
                assertThat(exportedText)
                        .contains("[J]")
                        .contains("PMID:")
                        .doesNotContain("${");
            }
            String completedAfterExport = mvc.perform(
                            get("/api/agent/tasks/{id}", taskId).cookie(ownerCookie))
                    .andExpect(status().isOk()).andReturn()
                    .getResponse().getContentAsString();
            assertThat(json.readTree(completedAfterExport).at("/data/status").asText())
                    .isEqualTo("COMPLETED");
            assertThat(json.readTree(completedAfterExport)
                    .at("/data/output/documentExport/schemaVersion").asText())
                    .isEqualTo("document-export/v2");
            templateObjectKey = jdbc.sql("""
                            select object_key from document_template_version
                            where hospital_id=:hospitalId and id=:templateVersionId
                            """)
                    .param("hospitalId", hospitalId)
                    .param("templateVersionId", templateVersionId)
                    .query(String.class).single();
            exportObjectKey = jdbc.sql("""
                            select object_key from document_export_record
                            where hospital_id=:hospitalId and id=:exportId
                            """)
                    .param("hospitalId", hospitalId)
                    .param("exportId", exportId)
                    .query(String.class).single();
            String persistedStrategy = jdbc.sql("""
                            select output_json::text from ai_agent_step_run
                            where hospital_id=:hospitalId and task_id=:taskId
                              and step_code='STEP_07_BUILD_SEARCH_STRATEGY'
                              and status='COMPLETED'
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query(String.class).single();
            assertThat(json.readTree(persistedStrategy).at("/pubmedQuery").asText())
                    .endsWith("NOT animals[MeSH Terms]");
            Map<String, Object> persistedSearch = jdbc.sql("""
                            select status,total_result_count,returned_result_count,
                                raw_object_key,raw_response_sha256,tool_version
                            from literature_search_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                              and database_name='PUBMED'
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedSearch)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("total_result_count", 2L)
                    .containsEntry("returned_result_count", 2)
                    .containsEntry("tool_version", "pubmed-mock/v1");
            searchRawObjectKey = (String) persistedSearch.get("raw_object_key");
            assertThat((String) persistedSearch.get("raw_response_sha256"))
                    .matches("[0-9a-f]{64}");
            assertThat(objectStorage.get(searchRawObjectKey)).isNotEmpty();
            Map<String, Object> persistedTrialSearch = jdbc.sql("""
                            select status,total_result_count,returned_result_count,
                                raw_object_key,raw_response_sha256,tool_version
                            from literature_search_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                              and database_name='CLINICAL_TRIALS_GOV'
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedTrialSearch)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("total_result_count", 2L)
                    .containsEntry("returned_result_count", 2)
                    .containsEntry("tool_version", "clinicaltrials-api-v2-mock/v1");
            trialRawObjectKey = (String) persistedTrialSearch.get("raw_object_key");
            assertThat((String) persistedTrialSearch.get("raw_response_sha256"))
                    .matches("[0-9a-f]{64}");
            assertThat(objectStorage.get(trialRawObjectKey)).isNotEmpty();
            Integer persistedLiterature = jdbc.sql("""
                            select count(*) from project_literature
                            where hospital_id=:hospitalId and project_id=:projectId
                            """).param("hospitalId", hospitalId).param("projectId", projectId)
                    .query(Integer.class).single();
            assertThat(persistedLiterature).isEqualTo(2);
            Integer persistedTrials = jdbc.sql("""
                            select count(*) from project_clinical_trial
                            where hospital_id=:hospitalId and project_id=:projectId
                            """).param("hospitalId", hospitalId).param("projectId", projectId)
                    .query(Integer.class).single();
            assertThat(persistedTrials).isEqualTo(2);
            Map<String, Object> persistedValidation = jdbc.sql("""
                            select status,validation_count,evidence_link_count,
                                raw_object_key,raw_response_sha256,tool_version,
                                external_request_count,cache_hit_count
                            from literature_validation_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedValidation)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("validation_count", 2)
                    .containsEntry("evidence_link_count", 2)
                    .containsEntry("tool_version", "crossref-rest-mock/v1");
            validationRawObjectKey = (String) persistedValidation.get("raw_object_key");
            assertThat((String) persistedValidation.get("raw_response_sha256"))
                    .matches("[0-9a-f]{64}");
            assertThat(objectStorage.get(validationRawObjectKey)).isNotEmpty();
            Integer persistedCitations = jdbc.sql("""
                            select count(*) from citation_validation_record
                            where validation_task_id in (
                                select id from literature_validation_task
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedCitations).isEqualTo(2);
            Integer persistedLinks = jdbc.sql("""
                            select count(*) from evidence_source_link
                            where validation_task_id in (
                                select id from literature_validation_task
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedLinks).isEqualTo(2);
            Map<String, Object> persistedAnalysis = jdbc.sql("""
                            select status,analyzed_source_count,excluded_citation_count,
                                high_similarity_count,moderate_similarity_count,
                                low_similarity_count,gap_count,input_sha256,algorithm_version
                            from similar_research_analysis_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedAnalysis)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("analyzed_source_count", 4)
                    .containsEntry("excluded_citation_count", 0)
                    .containsEntry("algorithm_version", "deterministic-peco-overlap/v1");
            assertThat((String) persistedAnalysis.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            Integer persistedComparisons = jdbc.sql("""
                            select count(*) from similar_research_comparison
                            where analysis_task_id in (
                                select id from similar_research_analysis_task
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedComparisons).isEqualTo(4);
            Map<String, Object> persistedDesign = jdbc.sql("""
                            select status,recommended_study_type,
                                ready_for_protocol_draft,alternative_count,
                                input_sha256,algorithm_version
                            from observational_design_recommendation_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedDesign)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("ready_for_protocol_draft", true)
                    .containsEntry("alternative_count", 3)
                    .containsEntry("algorithm_version", "observational-design-rules/v1");
            assertThat((String) persistedDesign.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            Integer persistedAlternatives = jdbc.sql("""
                            select count(*) from observational_design_alternative
                            where recommendation_task_id in (
                                select id from observational_design_recommendation_task
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedAlternatives).isEqualTo(3);
            Map<String, Object> confirmedDesignStep = jdbc.sql("""
                            select status,confirmed_by,confirmed_at
                            from ai_agent_step_run
                            where hospital_id=:hospitalId and task_id=:taskId
                              and step_code='STEP_12_RECOMMEND_OBSERVATIONAL_DESIGN'
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(confirmedDesignStep)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("confirmed_by", ownerId);
            assertThat(confirmedDesignStep.get("confirmed_at")).isNotNull();
            Map<String, Object> persistedProtocol = jdbc.sql("""
                            select status,study_type,schema_version,generator_version,
                                input_sha256
                            from research_protocol
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedProtocol)
                    .containsEntry("status", "APPROVED")
                    .containsEntry("schema_version", "research-protocol-draft/v1")
                    .containsEntry(
                            "generator_version",
                            "deterministic-observational-protocol/v1");
            assertThat((String) persistedProtocol.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            Integer persistedSections = jdbc.sql("""
                            select count(*) from research_protocol_section
                            where protocol_id in (
                                select id from research_protocol
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedSections).isEqualTo(18);
            Integer persistedSectionVersions = jdbc.sql("""
                            select count(*) from research_protocol_section_version
                            where section_id in (
                                select id from research_protocol_section
                                where protocol_id in (
                                    select id from research_protocol
                                    where agent_task_id=:taskId
                                )
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedSectionVersions).isEqualTo(19);
            Map<String, Object> persistedStatisticalDraft = jdbc.sql("""
                            select status,study_type,outcome_type_status,parameter_count,
                                input_sha256,generator_version
                            from statistical_analysis_draft
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedStatisticalDraft)
                    .containsEntry("status", "DRAFT")
                    .containsEntry("outcome_type_status", "NEEDS_EXPERT_CONFIRMATION")
                    .containsEntry("parameter_count", 8)
                    .containsEntry(
                            "generator_version",
                            "deterministic-observational-statistics/v1");
            assertThat((String) persistedStatisticalDraft.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            Integer persistedSampleParameters = jdbc.sql("""
                            select count(*) from sample_size_parameter_requirement
                            where statistical_draft_id in (
                                select id from statistical_analysis_draft
                                where agent_task_id=:taskId
                            )
                              and value_status='MISSING_NEEDS_INPUT'
                              and value_text is null
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedSampleParameters).isEqualTo(8);
            Integer currentStatisticalVersion = jdbc.sql("""
                            select current_version_no from research_protocol_section
                            where protocol_id in (
                                select id from research_protocol
                                where agent_task_id=:taskId
                            )
                              and section_code='STATISTICAL_ANALYSIS'
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(currentStatisticalVersion).isEqualTo(2);
            Map<String, Object> persistedClaimValidation = jdbc.sql("""
                            select status,claim_count,citation_link_count,
                                abstract_only_claim_count,
                                needs_expert_review_claim_count,
                                input_sha256,validator_version
                            from claim_citation_validation_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedClaimValidation)
                    .containsEntry("status", "WAITING_EXPERT_REVIEW")
                    .containsEntry(
                            "validator_version",
                            "deterministic-claim-citation-linker/v1")
                    .containsEntry(
                            "claim_count",
                            claimValidation.path("claimCount").asInt())
                    .containsEntry(
                            "citation_link_count",
                            claimValidation.path("citationLinkCount").asInt());
            assertThat((String) persistedClaimValidation.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            Integer persistedClaims = jdbc.sql("""
                            select count(*) from research_claim
                            where validation_task_id in (
                                select id from claim_citation_validation_task
                                where agent_task_id=:taskId
                            )
                              and expert_confirmation_status='PENDING_REVIEW'
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedClaims)
                    .isEqualTo(claimValidation.path("claimCount").asInt());
            Integer persistedClaimLinks = jdbc.sql("""
                            select count(*) from claim_citation_link
                            where research_claim_id in (
                                select id from research_claim
                                where validation_task_id in (
                                    select id from claim_citation_validation_task
                                    where agent_task_id=:taskId
                                )
                            )
                              and evidence_scope='ABSTRACT_ONLY'
                              and manual_confirmation_status='PENDING_REVIEW'
                              and excerpt_sha256 is not null
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedClaimLinks)
                    .isEqualTo(claimValidation.path("citationLinkCount").asInt());
            Map<String, Object> persistedStrobeCheck = jdbc.sql("""
                            select status,study_type,total_item_count,covered_count,
                                partially_covered_count,missing_count,
                                not_applicable_count,needs_expert_review_count,
                                input_sha256,checker_version
                            from strobe_completeness_check_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedStrobeCheck)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry("total_item_count", 22)
                    .containsEntry(
                            "checker_version",
                            "deterministic-strobe-2007-precheck/v1");
            assertThat((String) persistedStrobeCheck.get("input_sha256"))
                    .matches("[0-9a-f]{64}");
            int persistedStatusTotal =
                    (Integer) persistedStrobeCheck.get("covered_count")
                    + (Integer) persistedStrobeCheck.get("partially_covered_count")
                    + (Integer) persistedStrobeCheck.get("missing_count")
                    + (Integer) persistedStrobeCheck.get("not_applicable_count")
                    + (Integer) persistedStrobeCheck.get("needs_expert_review_count");
            assertThat(persistedStatusTotal).isEqualTo(22);
            Integer persistedStrobeItems = jdbc.sql("""
                            select count(*) from strobe_completeness_check_item
                            where check_task_id in (
                                select id from strobe_completeness_check_task
                                where agent_task_id=:taskId
                            )
                            """).param("taskId", taskId).query(Integer.class).single();
            assertThat(persistedStrobeItems).isEqualTo(22);
            Map<String, Object> persistedReview = jdbc.sql("""
                            select status,expert_decision,sections_locked,version
                            from research_review_task
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """)
                    .param("hospitalId", hospitalId)
                    .param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedReview)
                    .containsEntry("status", "APPROVED")
                    .containsEntry("expert_decision", "APPROVE")
                    .containsEntry("sections_locked", true)
                    .containsEntry("version", 2L);
            Integer persistedReviewComments = jdbc.sql("""
                            select count(*) from research_review_comment
                            where review_task_id in (
                                select id from research_review_task
                                where agent_task_id=:taskId
                            )
                            """)
                    .param("taskId", taskId)
                    .query(Integer.class).single();
            assertThat(persistedReviewComments).isEqualTo(1);
            Integer persistedReviewActions = jdbc.sql("""
                            select count(*) from research_review_action
                            where review_task_id in (
                                select id from research_review_task
                                where agent_task_id=:taskId
                            )
                            """)
                    .param("taskId", taskId)
                    .query(Integer.class).single();
            assertThat(persistedReviewActions).isEqualTo(4);
            Map<String, Object> persistedExport = jdbc.sql("""
                            select status,citation_style_version_id,
                                citation_count,content_sha256,content_size
                            from document_export_record
                            where hospital_id=:hospitalId and agent_task_id=:taskId
                            """)
                    .param("hospitalId", hospitalId)
                    .param("taskId", taskId)
                    .query().singleRow();
            assertThat(persistedExport)
                    .containsEntry("status", "COMPLETED")
                    .containsEntry(
                            "citation_style_version_id",
                            citationStyleVersionId);
            assertThat((Integer) persistedExport.get("citation_count")).isPositive();
            assertThat((String) persistedExport.get("content_sha256"))
                    .matches("[0-9a-f]{64}");
            assertThat((Long) persistedExport.get("content_size")).isPositive();
            Integer lockedSections = jdbc.sql("""
                            select count(*) from research_protocol_section
                            where protocol_id in (
                                select protocol_id from research_review_task
                                where agent_task_id=:taskId
                            ) and status='LOCKED'
                            """)
                    .param("taskId", taskId)
                    .query(Integer.class).single();
            assertThat(lockedSections).isEqualTo(18);
            Integer persistedEvents = jdbc.sql("""
                            select count(*) from ai_agent_event
                            where hospital_id=:hospitalId and task_id=:taskId
                            """).param("hospitalId", hospitalId).param("taskId", taskId)
                    .query(Integer.class).single();
            assertThat(persistedEvents).isGreaterThanOrEqualTo(12);

            String auditResponse = mvc.perform(get("/api/audits").cookie(ownerCookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            assertThat(json.readTree(auditResponse).at("/data").size()).isPositive();

            if (Boolean.parseBoolean(System.getenv("MINIO_ENABLED"))) {
                String expectedScanEngine = fileScanMode.equals("clamav")
                        ? "CLAMAV"
                        : "BASIC_SIGNATURE";
                var file = new MockMultipartFile("file", "anonymous.docx",
                        "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                        docx("anonymous cohort research material"));
                mvc.perform(multipart("/api/research/projects/{id}/files", projectId)
                                .file(file).cookie(ownerCookie).with(csrf()))
                        .andExpect(status().isOk())
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.data.securityStatus").value("SAFE"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.data.scanEngine").value(expectedScanEngine))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.data.extractionStatus").value("EXTRACTED"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers
                                .jsonPath("$.data.extractedCharacters").value(
                                        "anonymous cohort research material".length()));
                uploadedObjectKey = jdbc.sql(
                                "select object_key from project_file where project_id=:projectId")
                        .param("projectId", projectId).query(String.class).single();
                Map<String, Object> fileMetadata = jdbc.sql("""
                                select scan_engine,extraction_status,extracted_characters
                                from project_file where project_id=:projectId
                                """).param("projectId", projectId).query().singleRow();
                assertThat(fileMetadata).containsEntry("scan_engine", expectedScanEngine)
                        .containsEntry("extraction_status", "EXTRACTED")
                        .containsEntry("extracted_characters",
                                "anonymous cohort research material".length());
            }
        } finally {
            if (exportObjectKey != null) objectStorage.delete(exportObjectKey);
            if (templateObjectKey != null) objectStorage.delete(templateObjectKey);
            Map<String, Object> users = Map.of(
                    "owner", ownerId, "viewer", viewerId, "expert", expertId,
                    "medicalExpert", medicalExpertId);
            jdbc.sql("""
                    delete from user_session
                    where user_id in (:owner,:viewer,:expert,:medicalExpert)
                    """)
                    .params(users).update();
            jdbc.sql("""
                    delete from operation_audit
                    where actor_user_id in (:owner,:viewer,:expert,:medicalExpert)
                    """).params(users).update();
            jdbc.sql("""
                    delete from idempotency_record
                    where user_id in (:owner,:viewer,:expert,:medicalExpert)
                    """).params(users).update();
            if (projectId != null) {
                if (uploadedObjectKey != null) objectStorage.delete(uploadedObjectKey);
                if (searchRawObjectKey != null) objectStorage.delete(searchRawObjectKey);
                if (trialRawObjectKey != null) objectStorage.delete(trialRawObjectKey);
                if (validationRawObjectKey != null) objectStorage.delete(validationRawObjectKey);
                jdbc.sql("delete from project_file where project_id=:id").param("id", projectId).update();
                if (taskId != null) {
                    jdbc.sql("delete from document_export_record where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from research_review_task where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from strobe_completeness_check_task "
                                    + "where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from claim_citation_validation_task "
                                    + "where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from statistical_analysis_draft where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from research_protocol where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from observational_design_recommendation_task "
                                    + "where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from similar_research_analysis_task where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from literature_validation_task where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from project_clinical_trial where search_task_id in "
                                    + "(select id from literature_search_task where agent_task_id=:id)")
                            .param("id", taskId).update();
                    jdbc.sql("delete from project_literature where search_task_id in "
                                    + "(select id from literature_search_task where agent_task_id=:id)")
                            .param("id", taskId).update();
                    jdbc.sql("delete from literature_search_task where agent_task_id=:id")
                            .param("id", taskId).update();
                    jdbc.sql("delete from literature_record where hospital_id=:hospitalId")
                            .param("hospitalId", hospitalId).update();
                    jdbc.sql("delete from clinical_trial_record where hospital_id=:hospitalId")
                            .param("hospitalId", hospitalId).update();
                    jdbc.sql("delete from ai_agent_event where task_id=:id").param("id", taskId).update();
                    jdbc.sql("delete from ai_agent_step_run where task_id=:id").param("id", taskId).update();
                    jdbc.sql("delete from ai_agent_task where id=:id").param("id", taskId).update();
                }
                jdbc.sql("delete from project_member where project_id=:id").param("id", projectId).update();
                jdbc.sql("delete from research_project where id=:id").param("id", projectId).update();
            }
            jdbc.sql("delete from document_template_version where hospital_id=:hospitalId")
                    .param("hospitalId", hospitalId).update();
            jdbc.sql("delete from citation_style_version where hospital_id=:hospitalId")
                    .param("hospitalId", hospitalId).update();
            jdbc.sql("""
                    delete from user_role
                    where user_id in (:owner,:viewer,:expert,:medicalExpert)
                    """)
                    .params(users).update();
            jdbc.sql("""
                    delete from platform_user
                    where id in (:owner,:viewer,:expert,:medicalExpert)
                    """)
                    .params(users).update();
            jdbc.sql("delete from hospital where id=:id").param("id", hospitalId).update();
        }
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username, String password) {
        return user(id, hospitalId, username, password, Set.of(Role.DOCTOR));
    }

    private IdentityRepository.UserData user(
            UUID id, UUID hospitalId, String username, String password, Set<Role> roles) {
        return new IdentityRepository.UserData(id, hospitalId, username, encoder.encode(password),
                roles, true, false, 0, null);
    }

    private Cookie login(String hospitalCode, String username, String password) throws Exception {
        String body = json.writeValueAsString(Map.of(
                "hospitalCode", hospitalCode, "username", username, "password", password));
        String setCookie = mvc.perform(post("/api/auth/login")
                        .contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getHeader("Set-Cookie");
        assertThat(setCookie).isNotBlank();
        String token = setCookie.substring("MEDICAL_SESSION=".length(), setCookie.indexOf(';'));
        return new Cookie("MEDICAL_SESSION", token);
    }

    private byte[] docx(String text) throws Exception {
        try (var document = new XWPFDocument(); var output = new ByteArrayOutputStream()) {
            document.createParagraph().createRun().setText(text);
            document.write(output);
            return output.toByteArray();
        }
    }

    private String waitForTaskStatus(UUID taskId, Cookie cookie, String expected) throws Exception {
        String response = "";
        for (int attempt = 0; attempt < 50; attempt++) {
            response = mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(cookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if (expected.equals(json.readTree(response).at("/data/status").asText())) return response;
            Thread.sleep(100);
        }
        throw new AssertionError("Agent任务未进入状态 " + expected + ": " + response);
    }

    private String waitForTaskStep(UUID taskId, Cookie cookie, String expected) throws Exception {
        String response = "";
        for (int attempt = 0; attempt < 50; attempt++) {
            response = mvc.perform(get("/api/agent/tasks/{id}", taskId).cookie(cookie))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
            if (expected.equals(json.readTree(response).at("/data/currentStep").asText())) return response;
            Thread.sleep(100);
        }
        throw new AssertionError("Agent任务未进入步骤 " + expected + ": " + response);
    }

    private String answerFor(String question) {
        if (question.contains("门诊")) return "来自门诊电子病历数据库";
        if (question.contains("暴露和对照")) return "按首次处方日期分组，并设同类药物对照";
        if (question.contains("主要结局")) return "主要结局为12个月eGFR绝对变化";
        return "观察12个月，可获得年龄、性别、基线eGFR和合并用药";
    }
}
