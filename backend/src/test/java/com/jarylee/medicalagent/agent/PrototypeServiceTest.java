package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.mock.MockModelRouter;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.document.ControlledDocxService;
import com.jarylee.medicalagent.literature.MockPubMedGateway;
import com.jarylee.medicalagent.agent.prompt.PromptTemplateRegistry;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;

import static org.assertj.core.api.Assertions.assertThat;

class PrototypeServiceTest {
    private final PrototypeService service = new PrototypeService(
            new MockModelRouter(new MockResearchModel()), new MockPubMedGateway(),
            new ControlledDocxService(), new ResearchOutputValidator(), new PromptTemplateRegistry());
    private static final String IDEA = "我想研究2型糖尿病患者使用SGLT2抑制剂后肾功能的变化";

    @Test
    void completesTheStageZeroVerticalFlowWithVerifiedMockCitations() {
        var result = service.run(IDEA, "DIR-02");
        assertThat(result.analysis().directions()).hasSize(3);
        assertThat(result.analysis().clarificationQuestions()).isNotEmpty();
        assertThat(result.peco().schemaVersion()).isEqualTo("peco/v1");
        assertThat(result.literature()).allMatch(item -> item.verified() && item.pmid() != null);
        assertThat(result.background()).contains("[CIT-001, CIT-002]");
    }

    @Test
    void buildsResearchQuestionWithoutCallingLiteratureGateway() {
        PrototypeService questionOnly = new PrototypeService(
                new MockModelRouter(new MockResearchModel()),
                query -> {
                    throw new AssertionError("STEP06/STEP07 不应执行文献检索");
                },
                new ControlledDocxService(), new ResearchOutputValidator(),
                new PromptTemplateRegistry());

        var result = questionOnly.buildResearchQuestion(IDEA, "DIR-02");

        assertThat(result.peco().schemaVersion()).isEqualTo("peco/v1");
        assertThat(result.selectedDirection().id()).isEqualTo("DIR-02");
    }

    @Test
    void fillsTheControlledDocxTemplate() throws Exception {
        byte[] docx = service.export(IDEA, "DIR-02");
        try (XWPFDocument document = new XWPFDocument(new ByteArrayInputStream(docx))) {
            String text = document.getParagraphs().stream()
                    .map(p -> p.getText()).reduce("", (left, right) -> left + right);
            assertThat(text).contains("回顾性队列研究", "PMID:36331190");
            assertThat(text).doesNotContain("${");
        }
    }
}
