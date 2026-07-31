package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.ResearchModels.AnalysisResult;
import org.springframework.stereotype.Component;

import java.util.HashSet;

@Component
public class ResearchOutputValidator {
    private static final String REQUIRED_DISCLAIMER =
            "仅供科研设计讨论，未经伦理和科研管理审批";

    public AnalysisResult validate(AnalysisResult result) {
        if (result == null || !"research-analysis/v1".equals(result.schemaVersion())) {
            throw new IllegalArgumentException("模型输出Schema版本不受支持");
        }
        if (result.profile() == null || result.clarificationQuestions() == null
                || result.clarificationQuestions().isEmpty()) {
            throw new IllegalArgumentException("缺少结构化研究要素或澄清问题");
        }
        if (result.directions() == null || result.directions().size() != 3) {
            throw new IllegalArgumentException("第一版必须返回三个观察性研究方向");
        }
        if (result.disclaimer() == null
                || !result.disclaimer().contains(REQUIRED_DISCLAIMER)) {
            throw new IllegalArgumentException("模型输出缺少强制科研草案声明");
        }
        var ids = new HashSet<String>();
        result.directions().forEach(direction -> {
            if (direction.id() == null || direction.title() == null || direction.recommendedStudyType() == null) {
                throw new IllegalArgumentException("研究方向字段不完整");
            }
            if (!ids.add(direction.id())) {
                throw new IllegalArgumentException("研究方向标识重复");
            }
        });
        return result;
    }
}
