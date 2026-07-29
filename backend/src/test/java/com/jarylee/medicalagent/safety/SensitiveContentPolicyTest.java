package com.jarylee.medicalagent.safety;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SensitiveContentPolicyTest {
    private final SensitiveContentPolicy policy = new SensitiveContentPolicy();

    @Test
    void allowsAnonymousResearchIdea() {
        assertThat(policy.assess("研究2型糖尿病患者使用某类药物后肾功能变化").canSendToExternalModel())
                .isTrue();
    }

    @Test
    void blocksIdentityAndMedicalRecordIdentifiersFromExternalModels() {
        var assessment = policy.assess("患者身份证110101199001011234，住院号：ZY-123456");
        assertThat(assessment.status()).isEqualTo(SensitiveContentPolicy.Status.BLOCKED_FOR_EXTERNAL_MODEL);
        assertThat(assessment.matchedRules()).contains("CHINESE_ID", "INPATIENT_NUMBER");
        assertThat(assessment.canSendToExternalModel()).isFalse();
    }

    @Test
    void blocksDirectContactAndPatientDemographics() {
        var assessment = policy.assess(
                "患者姓名：张三，出生日期：1980-01-02，邮箱：patient@example.org，"
                        + "家庭住址：北京市某区某街道100号");

        assertThat(assessment.matchedRules()).contains(
                "PATIENT_NAME", "DATE_OF_BIRTH", "EMAIL_ADDRESS", "POSTAL_ADDRESS");
        assertThat(assessment.canSendToExternalModel()).isFalse();
    }
}
