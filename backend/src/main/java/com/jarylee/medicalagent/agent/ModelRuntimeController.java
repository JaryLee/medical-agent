package com.jarylee.medicalagent.agent;

import com.jarylee.medicalagent.agent.model.LogicalModelType;
import com.jarylee.medicalagent.agent.model.ModelRouter;
import com.jarylee.medicalagent.common.ApiResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/runtime")
public class ModelRuntimeController {
    private final ModelRouter modelRouter;
    private final String mode;
    private final String modelName;
    private final boolean externalEnabled;

    public ModelRuntimeController(
            ModelRouter modelRouter,
            @Value("${medical.model.mode:mock}") String mode,
            @Value("${medical.model.name:}") String modelName,
            @Value("${medical.model.external-enabled:false}") boolean externalEnabled) {
        this.modelRouter = modelRouter;
        this.mode = mode;
        this.modelName = modelName;
        this.externalEnabled = externalEnabled;
    }

    @GetMapping("/model")
    public ApiResponse<ModelRuntime> model() {
        String provider = modelRouter.route(LogicalModelType.RESEARCH_FAST).provider();
        return ApiResponse.ok(new ModelRuntime(provider, mode, modelName, externalEnabled));
    }

    public record ModelRuntime(
            String provider,
            String mode,
            String modelName,
            boolean externalEnabled) {}
}
