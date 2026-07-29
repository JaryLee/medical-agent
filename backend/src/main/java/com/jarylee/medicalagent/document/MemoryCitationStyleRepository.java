package com.jarylee.medicalagent.document;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryCitationStyleRepository implements CitationStyleRepository {
    private final Map<UUID, StyleData> styles = new ConcurrentHashMap<>();

    @Override
    public int nextVersion(UUID hospitalId, String styleCode) {
        return styles.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.styleCode().equals(styleCode))
                .mapToInt(StyleData::versionNo)
                .max().orElse(0) + 1;
    }

    @Override
    public StyleData create(StyleData style) {
        styles.put(style.id(), style);
        return style;
    }

    @Override
    public List<StyleData> findAll(UUID hospitalId) {
        return styles.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId))
                .sorted(Comparator.comparing(StyleData::createdAt).reversed())
                .toList();
    }

    @Override
    public Optional<StyleData> findById(UUID hospitalId, UUID styleId) {
        return Optional.ofNullable(styles.get(styleId))
                .filter(value -> value.hospitalId().equals(hospitalId));
    }

    @Override
    public synchronized Optional<StyleData> publish(
            UUID hospitalId, UUID styleId, UUID publishedBy,
            Instant publishedAt, long expectedVersion) {
        StyleData current = styles.get(styleId);
        if (current == null || !current.hospitalId().equals(hospitalId)
                || current.version() != expectedVersion
                || !"VALIDATED".equals(current.status())) {
            return Optional.empty();
        }
        styles.replaceAll((id, value) -> value.hospitalId().equals(hospitalId)
                && value.styleCode().equals(current.styleCode())
                && "PUBLISHED".equals(value.status())
                ? copyStatus(value, "ARCHIVED",
                        value.publishedBy(), value.publishedAt())
                : value);
        StyleData published = copyStatus(
                current, "PUBLISHED", publishedBy, publishedAt);
        styles.put(styleId, published);
        return Optional.of(published);
    }

    private StyleData copyStatus(
            StyleData source, String status,
            UUID publishedBy, Instant publishedAt) {
        return new StyleData(
                source.id(), source.hospitalId(), source.styleCode(),
                source.styleName(), source.versionNo(), status,
                source.layout(), source.authorLimit(), source.etAlText(),
                source.includePmid(), source.includeDoi(),
                source.includeEvidenceScope(), source.evidenceScopeLabel(),
                source.createdBy(), source.createdAt(), publishedBy,
                publishedAt, source.version() + 1);
    }
}
