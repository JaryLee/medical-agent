package com.jarylee.medicalagent.workflow;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.agent.mock.MockResearchModel;
import com.jarylee.medicalagent.agent.model.ResearchModels.StudyType;
import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ObservationalStudyRuleServiceTest {
    private final ObservationalStudyRuleRegistry registry =
            new ObservationalStudyRuleRegistry(new ObjectMapper());
    private final ObservationalStudyRuleService rules = new ObservationalStudyRuleService(registry);
    private final AnalysisResult analysis = new MockResearchModel().analyzeIdea(
            "研究2型糖尿病患者使用SGLT2抑制剂后的肾功能变化");

    @Test
    void blocksCohortDraftUntilRequiredClarificationsAreAnswered() {
        var missing = rules.assess(StudyType.COHORT, analysis, Map.of());
        var ready = rules.assess(StudyType.COHORT, analysis, Map.of(
                analysis.clarificationQuestions().get(0), "来自门诊电子病历数据库",
                analysis.clarificationQuestions().get(1), "按首次处方日期分组，并设同类药物对照",
                analysis.clarificationQuestions().get(2), "主要结局为12个月eGFR绝对变化",
                analysis.clarificationQuestions().get(3), "观察12个月并控制基线混杂"
        ));

        assertThat(missing.readyForDraft()).isFalse();
        assertThat(missing.missingFields()).contains("comparator", "setting", "timeFrame");
        assertThat(ready.readyForDraft()).isTrue();
        assertThat(ready.missingFields()).isEmpty();
        assertThat(ready.explanation()).contains("时间零点", "混杂控制");
        assertThat(ready.ruleVersion()).isEqualTo(ObservationalStudyRuleRegistry.CURRENT_VERSION);
    }
}
