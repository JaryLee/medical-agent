package com.jarylee.medicalagent.agent.evaluation;

import com.jarylee.medicalagent.workspace.WorkspaceModels;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/research/model-evaluations")
public class ModelEvaluationController {
    private final ModelEvaluationGovernanceService service;

    public ModelEvaluationController(
            ModelEvaluationGovernanceService service) {
        this.service = service;
    }

    @PostMapping
    public WorkspaceModels.Envelope<
            ModelEvaluationGovernanceService.EvaluationView>
    start(@RequestHeader("Idempotency-Key") String idempotencyKey) {
        return service.start(idempotencyKey);
    }

    @GetMapping
    public WorkspaceModels.Envelope<List<
            ModelEvaluationGovernanceService.EvaluationView>> list() {
        return service.list();
    }

    @GetMapping("/{evaluationKey}")
    public WorkspaceModels.Envelope<
            ModelEvaluationGovernanceService.EvaluationView> get(
            @PathVariable String evaluationKey) {
        return service.get(evaluationKey);
    }

    @PostMapping("/{evaluationKey}/expert-scores")
    public WorkspaceModels.Envelope<
            ModelEvaluationGovernanceService.EvaluationView>
    score(
            @PathVariable String evaluationKey,
            @RequestHeader("Idempotency-Key") String idempotencyKey,
            @RequestBody
            ModelEvaluationGovernanceService.ScoreRequest request) {
        return service.submitScore(
                evaluationKey, idempotencyKey, request);
    }
}
