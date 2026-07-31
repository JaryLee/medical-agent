package com.jarylee.medicalagent.workflow;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
@ConditionalOnProperty(
        prefix = "medical.agent",
        name = "model-audit-retention-cleanup-enabled",
        havingValue = "true",
        matchIfMissing = true)
public class ModelCallRetentionService {
    private static final Logger LOG =
            LoggerFactory.getLogger(ModelCallRetentionService.class);
    private static final int BATCH_SIZE = 500;

    private final ModelCallAuditRepository repository;
    private final Clock clock;

    public ModelCallRetentionService(
            ModelCallAuditRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @Scheduled(
            fixedDelayString =
                    "${medical.agent.model-audit-retention-cleanup-delay:PT24H}",
            initialDelayString =
                    "${medical.agent.model-audit-retention-cleanup-initial-delay:PT60S}")
    public void sweep() {
        RetentionSweepResult result = sweepNow();
        if (result.payloadsPurged() > 0 || result.metadataPurged() > 0) {
            LOG.info(
                    "模型调用审计保留期清理完成: payloadsPurged={}, metadataPurged={}",
                    result.payloadsPurged(), result.metadataPurged());
        }
        if (result.expiredObjectPayloads() > 0) {
            LOG.warn(
                    "有 {} 条加密对象负载已到期但未自动删除；必须先完成对象存储销毁再清理元数据",
                    result.expiredObjectPayloads());
        }
    }

    RetentionSweepResult sweepNow() {
        var now = clock.instant();
        int payloads = repository.purgeExpiredPayloadSnapshots(now, BATCH_SIZE);
        int metadata = repository.purgeExpiredMetadata(now, BATCH_SIZE);
        long objectPayloads = repository.countExpiredObjectPayloads(now);
        return new RetentionSweepResult(payloads, metadata, objectPayloads);
    }

    record RetentionSweepResult(
            int payloadsPurged, int metadataPurged,
            long expiredObjectPayloads) {}
}
