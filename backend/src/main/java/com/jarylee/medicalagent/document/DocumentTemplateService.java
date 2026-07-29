package com.jarylee.medicalagent.document;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.document.DocumentExportModels.TemplateView;
import com.jarylee.medicalagent.file.ObjectStorage;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.security.MessageDigest;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@Service
public class DocumentTemplateService {
    public static final String DEFAULT_CODE = "OBSERVATIONAL_PROTOCOL";
    private static final String DOCX_CONTENT_TYPE =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document";

    private final DocumentExportRepository repository;
    private final ControlledDocxTemplateEngine engine;
    private final ObjectStorage storage;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final ObjectMapper json;
    private final Clock clock;

    public DocumentTemplateService(
            DocumentExportRepository repository,
            ControlledDocxTemplateEngine engine,
            ObjectStorage storage,
            CurrentUserProvider currentUser,
            AuditService audit,
            ObjectMapper json,
            Clock clock) {
        this.repository = repository;
        this.engine = engine;
        this.storage = storage;
        this.currentUser = currentUser;
        this.audit = audit;
        this.json = json;
        this.clock = clock;
    }

    public List<TemplateView> list() {
        AuthenticatedUser actor = requireHospitalUser();
        return repository.findTemplates(actor.hospitalId()).stream()
                .map(this::view)
                .toList();
    }

    public TemplateView installDefault() {
        AuthenticatedUser actor = requireTemplateAdmin();
        var resource = new ClassPathResource(
                "templates/anonymous-research-protocol.docx");
        try (var input = resource.getInputStream()) {
            byte[] content = input.readAllBytes();
            return create(actor, DEFAULT_CODE, "观察性研究方案默认模板", content);
        } catch (Exception exception) {
            throw new IllegalStateException("无法读取内置 DOCX 模板", exception);
        }
    }

    public TemplateView upload(
            String templateCode, String templateName, MultipartFile file) {
        AuthenticatedUser actor = requireTemplateAdmin();
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("请选择 DOCX 模板");
        }
        String fileName = file.getOriginalFilename() == null
                ? "" : file.getOriginalFilename().toLowerCase(Locale.ROOT);
        if (!fileName.endsWith(".docx")) {
            throw new IllegalArgumentException("模板必须是 .docx 文件");
        }
        try {
            return create(
                    actor, templateCode, templateName, file.getBytes());
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalStateException("读取模板文件失败", exception);
        }
    }

    public TemplateView publish(UUID templateId, long expectedVersion) {
        AuthenticatedUser actor = requireTemplateAdmin();
        Instant now = clock.instant();
        var published = repository.publishTemplate(
                        actor.hospitalId(), templateId, actor.userId(),
                        now, expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "模板状态或版本已变化，只有校验通过的版本可以发布"));
        audit.record(actor, "DOCUMENT_TEMPLATE_PUBLISHED",
                "DOCUMENT_TEMPLATE_VERSION", templateId.toString());
        return view(published);
    }

    public PreviewFile preview(UUID templateId) {
        AuthenticatedUser actor = requireTemplateAdmin();
        var template = repository.findTemplate(actor.hospitalId(), templateId)
                .orElseThrow(() -> BusinessException.notFound("文档模板不存在"));
        byte[] document = engine.render(
                content(actor.hospitalId(), template),
                previewValues());
        audit.record(actor, "DOCUMENT_TEMPLATE_PREVIEWED",
                "DOCUMENT_TEMPLATE_VERSION", templateId.toString());
        return new PreviewFile(
                template.templateCode() + "-v" + template.versionNo()
                        + "-试生成.docx",
                DOCX_CONTENT_TYPE,
                document,
                sha256(document));
    }

    public DocumentExportRepository.TemplateData requirePublished(
            UUID hospitalId, UUID templateId) {
        var template = repository.findTemplate(hospitalId, templateId)
                .orElseThrow(() -> BusinessException.notFound("文档模板不存在"));
        if (!"PUBLISHED".equals(template.status())
                || !"VALID".equals(template.validationStatus())) {
            throw BusinessException.conflict("只能使用已发布且校验通过的模板");
        }
        return template;
    }

    public byte[] content(
            UUID hospitalId, DocumentExportRepository.TemplateData template) {
        if (!hospitalId.equals(template.hospitalId())
                || !template.objectKey().startsWith(
                "hospital/" + hospitalId + "/document-templates/")) {
            throw BusinessException.forbidden("模板对象不属于当前医院");
        }
        byte[] content = storage.get(template.objectKey());
        if (!template.contentSha256().equals(sha256(content))) {
            throw new IllegalStateException("模板文件哈希与发布记录不一致");
        }
        return content;
    }

    private TemplateView create(
            AuthenticatedUser actor, String rawCode, String rawName, byte[] content) {
        String code = normalizeCode(rawCode);
        String name = rawName == null ? "" : rawName.strip();
        if (name.isBlank() || name.length() > 200) {
            throw new IllegalArgumentException("模板名称长度必须为 1～200");
        }
        var validation = engine.validate(content);
        int versionNo = repository.nextTemplateVersion(actor.hospitalId(), code);
        UUID id = UUID.randomUUID();
        String objectKey = "hospital/" + actor.hospitalId()
                + "/document-templates/" + code.toLowerCase(Locale.ROOT)
                + "/v" + versionNo + "/" + id + ".docx";
        storage.put(objectKey, content, DOCX_CONTENT_TYPE);
        Instant now = clock.instant();
        var created = repository.createTemplate(
                new DocumentExportRepository.TemplateData(
                        id, actor.hospitalId(), code, name, versionNo,
                        "VALIDATED", objectKey, sha256(content), content.length,
                        ControlledDocxTemplateEngine.PLACEHOLDER_SCHEMA_VERSION,
                        write(validation.placeholders()), "VALID",
                        validation.message(), actor.userId(), now,
                        null, null, 0));
        audit.record(actor, "DOCUMENT_TEMPLATE_VALIDATED",
                "DOCUMENT_TEMPLATE_VERSION", id.toString());
        return view(created);
    }

    private Map<String, String> previewValues() {
        return Map.ofEntries(
                Map.entry("${project.title}", "匿名观察性研究方案（模板试生成）"),
                Map.entry("${project.principalInvestigator}", "匿名负责人"),
                Map.entry("${project.department}", "匿名科室"),
                Map.entry("${research.background}",
                        "此处展示经核验公开证据支持的研究背景示例。"),
                Map.entry("${research.question}",
                        "匿名暴露因素与匿名结局是否相关？"),
                Map.entry("${research.objectives}",
                        "验证模板章节、字体、间距和分页是否满足医院要求。"),
                Map.entry("${research.studyDesign}", "队列研究设计示例"),
                Map.entry("${research.population}", "匿名研究对象"),
                Map.entry("${research.inclusionCriteria}", "匿名纳入标准示例"),
                Map.entry("${research.exclusionCriteria}", "匿名排除标准示例"),
                Map.entry("${research.outcomes}", "匿名主要终点示例"),
                Map.entry("${research.variables}", "暴露、结局与协变量示例"),
                Map.entry("${research.statisticalPlan}", "统计分析计划示例"),
                Map.entry("${research.ethicalConsiderations}",
                        "伦理审批与数据安全要求示例"),
                Map.entry("${research.references}",
                        "[1] Anonymous Author. Verified public reference. "
                                + "PMID:00000000. [模板试生成数据]"));
    }

    private AuthenticatedUser requireHospitalUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.forcePasswordChange() || actor.hospitalId() == null) {
            throw BusinessException.forbidden("当前账号不能访问医院模板");
        }
        return actor;
    }

    private AuthenticatedUser requireTemplateAdmin() {
        AuthenticatedUser actor = requireHospitalUser();
        if (!actor.hasRole(Role.HOSPITAL_ADMIN)) {
            throw BusinessException.forbidden("只有医院管理员可以管理文档模板");
        }
        return actor;
    }

    private String normalizeCode(String value) {
        String code = value == null ? "" : value.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_-]{2,79}")) {
            throw new IllegalArgumentException(
                    "模板代码必须为 3～80 位大写字母、数字、下划线或连字符");
        }
        return code;
    }

    private TemplateView view(DocumentExportRepository.TemplateData value) {
        return new TemplateView(
                value.id(), value.templateCode(), value.templateName(),
                value.versionNo(), value.status(), value.contentSha256(),
                value.contentSize(), value.placeholderSchemaVersion(),
                readList(value.placeholdersJson()), value.validationStatus(),
                value.validationMessage(), value.createdBy(), value.createdAt(),
                value.publishedBy(), value.publishedAt(), value.version());
    }

    private String write(Object value) {
        try {
            return json.writeValueAsString(value);
        } catch (Exception exception) {
            throw new IllegalStateException("模板元数据序列化失败", exception);
        }
    }

    private List<String> readList(String value) {
        try {
            return json.readValue(value, new TypeReference<>() {});
        } catch (Exception exception) {
            throw new IllegalStateException("模板占位符记录损坏", exception);
        }
    }

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception exception) {
            throw new IllegalStateException("SHA-256 不可用", exception);
        }
    }

    public record PreviewFile(
            String fileName,
            String contentType,
            byte[] content,
            String sha256
    ) {}
}
