package com.jarylee.medicalagent.agent.evaluation;

import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
@Profile("postgres")
public class JdbcModelEvaluationRepository
        implements ModelEvaluationRepository {
    private static final String RUN_COLUMNS = """
            id,hospital_id,started_by,dataset_version,data_classification,
            prompt_version,route_policy_version,idempotency_key,request_sha256,
            status,case_count,passed_count,
            report_sha256,report_json::text,started_at,completed_at,version
            """;
    private static final String CASE_COLUMNS = """
            id,hospital_id,evaluation_run_id,case_key,logical_model_type,
            provider,model_name,output_sha256,passed,metrics_json::text,
            error_code,evaluated_at
            """;
    private static final String SCORE_COLUMNS = """
            id,hospital_id,evaluation_run_id,responsibility,reviewer_id,
            correctness_score,completeness_score,safety_score,
            actionability_score,recommendation,comment,idempotency_key,
            request_sha256,submitted_at
            """;

    private final JdbcClient jdbc;

    public JdbcModelEvaluationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public void createRun(RunData value) {
        jdbc.sql("""
                insert into model_evaluation_run(
                    id,hospital_id,started_by,dataset_version,
                    data_classification,prompt_version,route_policy_version,
                    idempotency_key,request_sha256,status,case_count,
                    passed_count,report_sha256,report_json,
                    started_at,completed_at,version
                ) values(
                    :id,:hospitalId,:startedBy,:datasetVersion,
                    :dataClassification,:promptVersion,:routePolicyVersion,
                    :idempotencyKey,:requestSha256,:status,:caseCount,
                    :passedCount,:reportSha256,
                    cast(:reportJson as jsonb),:startedAt,:completedAt,:version
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("startedBy", value.startedBy())
                .param("datasetVersion", value.datasetVersion())
                .param("dataClassification", value.dataClassification())
                .param("promptVersion", value.promptVersion())
                .param("routePolicyVersion", value.routePolicyVersion())
                .param("idempotencyKey", value.idempotencyKey())
                .param("requestSha256", value.requestSha256())
                .param("status", value.status())
                .param("caseCount", value.caseCount())
                .param("passedCount", value.passedCount())
                .param("reportSha256", value.reportSha256())
                .param("reportJson", value.reportJson())
                .param("startedAt", Timestamp.from(value.startedAt()))
                .param("completedAt", timestamp(value.completedAt()))
                .param("version", value.version())
                .update();
    }

    @Override
    public void saveCaseResult(CaseData value) {
        jdbc.sql("""
                insert into model_evaluation_case_result(
                    id,hospital_id,evaluation_run_id,case_key,
                    logical_model_type,provider,model_name,output_sha256,
                    passed,metrics_json,error_code,evaluated_at
                ) values(
                    :id,:hospitalId,:evaluationRunId,:caseKey,
                    :logicalModelType,:provider,:modelName,:outputSha256,
                    :passed,cast(:metricsJson as jsonb),:errorCode,:evaluatedAt
                )
                """)
                .param("id", value.id())
                .param("hospitalId", value.hospitalId())
                .param("evaluationRunId", value.evaluationRunId())
                .param("caseKey", value.caseKey())
                .param("logicalModelType", value.logicalModelType())
                .param("provider", value.provider())
                .param("modelName", value.modelName())
                .param("outputSha256", value.outputSha256())
                .param("passed", value.passed())
                .param("metricsJson", value.metricsJson())
                .param("errorCode", value.errorCode())
                .param("evaluatedAt", Timestamp.from(value.evaluatedAt()))
                .update();
    }

    @Override
    public void completeAutomation(
            UUID hospitalId, UUID runId, int passedCount,
            String reportSha256, String reportJson, Instant completedAt) {
        int updated = jdbc.sql("""
                update model_evaluation_run
                set status='WAITING_EXPERT_SCORING',
                    passed_count=:passedCount,
                    report_sha256=:reportSha256,
                    report_json=cast(:reportJson as jsonb),
                    completed_at=:completedAt,
                    version=version+1
                where hospital_id=:hospitalId and id=:runId
                  and status='RUNNING'
                """)
                .param("passedCount", passedCount)
                .param("reportSha256", reportSha256)
                .param("reportJson", reportJson)
                .param("completedAt", Timestamp.from(completedAt))
                .param("hospitalId", hospitalId)
                .param("runId", runId)
                .update();
        if (updated != 1) throw new IllegalStateException("模型评测批次状态冲突");
    }

    @Override
    public Optional<RunData> findRun(UUID hospitalId, UUID runId) {
        return jdbc.sql("select " + RUN_COLUMNS + """
                        from model_evaluation_run
                        where hospital_id=:hospitalId and id=:runId
                        """)
                .param("hospitalId", hospitalId)
                .param("runId", runId)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public Optional<RunData> findRunByStartIdempotency(
            UUID hospitalId, UUID startedBy, String idempotencyKey) {
        return jdbc.sql("select " + RUN_COLUMNS + """
                        from model_evaluation_run
                        where hospital_id=:hospitalId
                          and started_by=:startedBy
                          and idempotency_key=:idempotencyKey
                        """)
                .param("hospitalId", hospitalId)
                .param("startedBy", startedBy)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapRun)
                .optional();
    }

    @Override
    public List<RunData> findRuns(UUID hospitalId) {
        return jdbc.sql("select " + RUN_COLUMNS + """
                        from model_evaluation_run
                        where hospital_id=:hospitalId
                        order by started_at desc,id desc
                        """)
                .param("hospitalId", hospitalId)
                .query(this::mapRun)
                .list();
    }

    @Override
    public List<CaseData> findCaseResults(UUID hospitalId, UUID runId) {
        return jdbc.sql("select " + CASE_COLUMNS + """
                        from model_evaluation_case_result
                        where hospital_id=:hospitalId
                          and evaluation_run_id=:runId
                        order by case_key
                        """)
                .param("hospitalId", hospitalId)
                .param("runId", runId)
                .query(this::mapCase)
                .list();
    }

    @Override
    public void saveExpertScore(ExpertScoreData value) {
        try {
            String status = jdbc.sql("""
                            select status
                            from model_evaluation_run
                            where hospital_id=:hospitalId and id=:runId
                            for update
                            """)
                    .param("hospitalId", value.hospitalId())
                    .param("runId", value.evaluationRunId())
                    .query(String.class)
                    .optional()
                    .orElseThrow(() -> new IllegalStateException(
                            "模型评测批次不存在"));
            if (!"WAITING_EXPERT_SCORING".equals(status)) {
                throw new IllegalStateException("当前模型评测批次不可评分");
            }
            int inserted = jdbc.sql("""
                    insert into model_evaluation_expert_score(
                        id,hospital_id,evaluation_run_id,responsibility,
                        reviewer_id,correctness_score,completeness_score,
                        safety_score,actionability_score,recommendation,
                        comment,idempotency_key,request_sha256,submitted_at
                    ) values(
                        :id,:hospitalId,:evaluationRunId,:responsibility,
                        :reviewerId,:correctnessScore,:completenessScore,
                        :safetyScore,:actionabilityScore,:recommendation,
                        :comment,:idempotencyKey,:requestSha256,:submittedAt
                    )
                    on conflict do nothing
                    """)
                    .param("id", value.id())
                    .param("hospitalId", value.hospitalId())
                    .param("evaluationRunId", value.evaluationRunId())
                    .param("responsibility", value.responsibility())
                    .param("reviewerId", value.reviewerId())
                    .param("correctnessScore", value.correctnessScore())
                    .param("completenessScore", value.completenessScore())
                    .param("safetyScore", value.safetyScore())
                    .param("actionabilityScore", value.actionabilityScore())
                    .param("recommendation", value.recommendation())
                    .param("comment", value.comment())
                    .param("idempotencyKey", value.idempotencyKey())
                    .param("requestSha256", value.requestSha256())
                    .param("submittedAt", Timestamp.from(value.submittedAt()))
                    .update();
            if (inserted != 1) {
                throw new IllegalStateException(
                        "评测评分重复或违反独立性约束");
            }
        } catch (DataIntegrityViolationException exception) {
            throw new IllegalStateException(
                    "评测必须由两名不同专家分别评分", exception);
        }
    }

    @Override
    public Optional<ExpertScoreData> findExpertScoreByIdempotency(
            UUID hospitalId, UUID reviewerId, String idempotencyKey) {
        return jdbc.sql("select " + SCORE_COLUMNS + """
                        from model_evaluation_expert_score
                        where hospital_id=:hospitalId
                          and reviewer_id=:reviewerId
                          and idempotency_key=:idempotencyKey
                        """)
                .param("hospitalId", hospitalId)
                .param("reviewerId", reviewerId)
                .param("idempotencyKey", idempotencyKey)
                .query(this::mapScore)
                .optional();
    }

    @Override
    public List<ExpertScoreData> findExpertScores(
            UUID hospitalId, UUID runId) {
        return jdbc.sql("select " + SCORE_COLUMNS + """
                        from model_evaluation_expert_score
                        where hospital_id=:hospitalId
                          and evaluation_run_id=:runId
                        order by responsibility
                        """)
                .param("hospitalId", hospitalId)
                .param("runId", runId)
                .query(this::mapScore)
                .list();
    }

    @Override
    public void markCompleted(UUID hospitalId, UUID runId) {
        int updated = jdbc.sql("""
                update model_evaluation_run
                set status='COMPLETED',version=version+1
                where hospital_id=:hospitalId and id=:runId
                  and status='WAITING_EXPERT_SCORING'
                  and (
                    select count(*)
                    from model_evaluation_expert_score score
                    where score.hospital_id=:hospitalId
                      and score.evaluation_run_id=:runId
                  )=2
                """)
                .param("hospitalId", hospitalId)
                .param("runId", runId)
                .update();
        if (updated != 1) {
            throw new IllegalStateException("模型评测尚未完成双专家评分");
        }
    }

    private RunData mapRun(ResultSet result, int row) throws SQLException {
        return new RunData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("started_by", UUID.class),
                result.getString("dataset_version"),
                result.getString("data_classification"),
                result.getString("prompt_version"),
                result.getString("route_policy_version"),
                result.getString("idempotency_key"),
                result.getString("request_sha256"),
                result.getString("status"),
                result.getInt("case_count"),
                nullableInteger(result, "passed_count"),
                result.getString("report_sha256"),
                result.getString("report_json"),
                result.getTimestamp("started_at").toInstant(),
                instant(result, "completed_at"),
                result.getLong("version"));
    }

    private CaseData mapCase(ResultSet result, int row) throws SQLException {
        return new CaseData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("evaluation_run_id", UUID.class),
                result.getString("case_key"),
                result.getString("logical_model_type"),
                result.getString("provider"),
                result.getString("model_name"),
                result.getString("output_sha256"),
                result.getBoolean("passed"),
                result.getString("metrics_json"),
                result.getString("error_code"),
                result.getTimestamp("evaluated_at").toInstant());
    }

    private ExpertScoreData mapScore(
            ResultSet result, int row) throws SQLException {
        return new ExpertScoreData(
                result.getObject("id", UUID.class),
                result.getObject("hospital_id", UUID.class),
                result.getObject("evaluation_run_id", UUID.class),
                result.getString("responsibility"),
                result.getObject("reviewer_id", UUID.class),
                result.getShort("correctness_score"),
                result.getShort("completeness_score"),
                result.getShort("safety_score"),
                result.getShort("actionability_score"),
                result.getString("recommendation"),
                result.getString("comment"),
                result.getString("idempotency_key"),
                result.getString("request_sha256"),
                result.getTimestamp("submitted_at").toInstant());
    }

    private Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    private Instant instant(ResultSet result, String column)
            throws SQLException {
        Timestamp value = result.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private Integer nullableInteger(ResultSet result, String column)
            throws SQLException {
        int value = result.getInt(column);
        return result.wasNull() ? null : value;
    }
}
