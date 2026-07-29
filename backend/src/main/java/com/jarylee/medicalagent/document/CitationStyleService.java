package com.jarylee.medicalagent.document;

import com.jarylee.medicalagent.audit.AuditService;
import com.jarylee.medicalagent.auth.AuthenticatedUser;
import com.jarylee.medicalagent.auth.CurrentUserProvider;
import com.jarylee.medicalagent.auth.Role;
import com.jarylee.medicalagent.common.BusinessException;
import com.jarylee.medicalagent.document.CitationStyleModels.StyleView;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
public class CitationStyleService {
    public static final String DEFAULT_CODE = "INSTITUTION_NUMERIC";

    private final CitationStyleRepository repository;
    private final CurrentUserProvider currentUser;
    private final AuditService audit;
    private final Clock clock;

    public CitationStyleService(
            CitationStyleRepository repository,
            CurrentUserProvider currentUser,
            AuditService audit,
            Clock clock) {
        this.repository = repository;
        this.currentUser = currentUser;
        this.audit = audit;
        this.clock = clock;
    }

    public List<StyleView> list() {
        AuthenticatedUser actor = requireHospitalUser();
        return repository.findAll(actor.hospitalId()).stream()
                .map(this::view)
                .toList();
    }

    public StyleView installDefault() {
        return create(
                DEFAULT_CODE,
                "机构数字引用格式",
                "VANCOUVER",
                6,
                "等",
                true,
                true,
                "摘要级证据");
    }

    public StyleView create(
            String rawCode,
            String rawName,
            String rawLayout,
            int authorLimit,
            String rawEtAlText,
            boolean includeDoi,
            boolean includeEvidenceScope,
            String rawEvidenceScopeLabel) {
        AuthenticatedUser actor = requireAdmin();
        String code = normalizeCode(rawCode);
        String name = requiredText(rawName, "引用格式名称", 200);
        String layout = rawLayout == null
                ? "" : rawLayout.strip().toUpperCase(Locale.ROOT);
        if (!List.of("VANCOUVER", "GB_T_7714").contains(layout)) {
            throw new IllegalArgumentException(
                    "引用布局只支持 VANCOUVER 或 GB_T_7714");
        }
        if (authorLimit < 1 || authorLimit > 20) {
            throw new IllegalArgumentException("作者显示上限必须为 1～20");
        }
        String etAlText = requiredText(rawEtAlText, "省略作者标记", 30);
        String evidenceScopeLabel = requiredText(
                rawEvidenceScopeLabel, "证据范围标记", 80);
        var now = clock.instant();
        var created = repository.create(new CitationStyleRepository.StyleData(
                UUID.randomUUID(), actor.hospitalId(), code, name,
                repository.nextVersion(actor.hospitalId(), code),
                "VALIDATED", layout, authorLimit, etAlText,
                true, includeDoi, includeEvidenceScope,
                evidenceScopeLabel, actor.userId(), now,
                null, null, 0));
        audit.record(actor, "CITATION_STYLE_VERSION_CREATED",
                "CITATION_STYLE_VERSION", created.id().toString());
        return view(created);
    }

    public StyleView publish(UUID styleId, long expectedVersion) {
        AuthenticatedUser actor = requireAdmin();
        var published = repository.publish(
                        actor.hospitalId(), styleId, actor.userId(),
                        clock.instant(), expectedVersion)
                .orElseThrow(() -> BusinessException.conflict(
                        "引用格式状态或版本已变化，只有已校验版本可以发布"));
        audit.record(actor, "CITATION_STYLE_PUBLISHED",
                "CITATION_STYLE_VERSION", published.id().toString());
        return view(published);
    }

    public CitationStyleRepository.StyleData requirePublished(
            UUID hospitalId, UUID styleId) {
        var style = repository.findById(hospitalId, styleId)
                .orElseThrow(() -> BusinessException.notFound("引用格式不存在"));
        if (!"PUBLISHED".equals(style.status())) {
            throw BusinessException.conflict("只能使用已发布的医院引用格式");
        }
        return style;
    }

    public String versionLabel(CitationStyleRepository.StyleData style) {
        return style.styleCode() + "/v" + style.versionNo();
    }

    public String format(
            CitationStyleRepository.StyleData style,
            int index,
            CitationInput citation) {
        String authors = authors(style, citation.authors());
        String title = clean(citation.title());
        String journal = clean(citation.journal());
        String date = clean(citation.publicationDate());
        String authorText = authors.isBlank() ? "作者信息待核验" : authors;
        StringBuilder line = new StringBuilder()
                .append("[").append(index).append("] ")
                .append(authorText)
                .append(authorText.endsWith(".") ? " " : ". ");
        if ("GB_T_7714".equals(style.layout())) {
            line.append(title).append("[J]. ")
                    .append(journal).append(", ").append(date);
        } else {
            line.append(title).append(". ")
                    .append(journal).append(". ").append(date);
        }
        if (style.includePmid()) {
            line.append(". PMID:").append(clean(citation.pmid()));
        }
        if (style.includeDoi() && citation.doi() != null
                && !citation.doi().isBlank()) {
            line.append("; DOI:").append(clean(citation.doi()));
        }
        line.append(".");
        if (style.includeEvidenceScope()) {
            line.append(" [").append(style.evidenceScopeLabel()).append("]");
        }
        return line.toString();
    }

    private String authors(
            CitationStyleRepository.StyleData style, List<String> rawAuthors) {
        List<String> authors = new ArrayList<>();
        for (String value : rawAuthors) {
            String author = clean(value);
            if (!author.isBlank()) authors.add(author);
        }
        if (authors.size() > style.authorLimit()) {
            return String.join(", ", authors.subList(0, style.authorLimit()))
                    + ", " + style.etAlText();
        }
        return String.join(", ", authors);
    }

    private String clean(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").strip();
    }

    private String normalizeCode(String rawCode) {
        String code = rawCode == null
                ? "" : rawCode.strip().toUpperCase(Locale.ROOT);
        if (!code.matches("[A-Z][A-Z0-9_]{1,79}")) {
            throw new IllegalArgumentException(
                    "引用格式代码必须为 2～80 位大写字母、数字或下划线");
        }
        return code;
    }

    private String requiredText(String raw, String field, int maxLength) {
        String value = raw == null ? "" : raw.strip();
        if (value.isBlank() || value.length() > maxLength) {
            throw new IllegalArgumentException(
                    field + "长度必须为 1～" + maxLength);
        }
        return value;
    }

    private AuthenticatedUser requireHospitalUser() {
        AuthenticatedUser actor = currentUser.requireUser();
        if (actor.hospitalId() == null || actor.forcePasswordChange()) {
            throw BusinessException.forbidden("当前账号不能访问医院引用格式");
        }
        return actor;
    }

    private AuthenticatedUser requireAdmin() {
        AuthenticatedUser actor = requireHospitalUser();
        if (!actor.roles().contains(Role.HOSPITAL_ADMIN)) {
            throw BusinessException.forbidden("只有医院管理员可以管理引用格式");
        }
        return actor;
    }

    private StyleView view(CitationStyleRepository.StyleData value) {
        return new StyleView(
                value.id(), value.styleCode(), value.styleName(),
                value.versionNo(), value.status(), value.layout(),
                value.authorLimit(), value.etAlText(), value.includePmid(),
                value.includeDoi(), value.includeEvidenceScope(),
                value.evidenceScopeLabel(), value.createdBy(),
                value.createdAt(), value.publishedBy(),
                value.publishedAt(), value.version());
    }

    public record CitationInput(
            List<String> authors,
            String title,
            String journal,
            String publicationDate,
            String pmid,
            String doi
    ) {}
}
