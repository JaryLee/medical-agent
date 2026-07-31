package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.util.ArrayList;

@Repository
@Profile("memory")
public class MemoryResearchProtocolRepository implements ResearchProtocolRepository {
    private final Map<UUID, ProtocolData> protocols = new ConcurrentHashMap<>();
    private final Map<UUID, List<ResearchProtocolModels.ProtocolSection>> sections =
            new ConcurrentHashMap<>();
    private final Map<UUID, List<SectionVersionData>> versions =
            new ConcurrentHashMap<>();

    @Override
    public Optional<ProtocolData> findByAgentTask(
            UUID hospitalId, UUID agentTaskId) {
        return protocols.values().stream()
                .filter(value -> value.hospitalId().equals(hospitalId)
                        && value.agentTaskId().equals(agentTaskId))
                .findFirst();
    }

    @Override
    public List<SectionData> findSections(
            UUID hospitalId, UUID protocolId) {
        ProtocolData protocol = protocols.get(protocolId);
        if (protocol == null
                || !protocol.hospitalId().equals(hospitalId)) {
            return List.of();
        }
        return sections.getOrDefault(protocolId, List.of()).stream()
                .map(section -> new SectionData(
                        section.sectionId(), hospitalId, protocolId,
                        section.sectionCode(), section.title(),
                        section.sortOrder(), section.versionNo(), "DRAFT"))
                .toList();
    }

    @Override
    public List<SectionVersionData> findSectionVersions(
            UUID hospitalId, UUID sectionId) {
        return versions.getOrDefault(sectionId, List.of()).stream()
                .filter(value -> value.hospitalId().equals(hospitalId))
                .toList();
    }

    @Override
    public List<ProjectSectionVersionData> findProjectSectionVersions(
            UUID hospitalId, UUID projectId, String sectionCode) {
        List<ProjectSectionVersionData> result = new ArrayList<>();
        protocols.values().stream()
                .filter(protocol ->
                        protocol.hospitalId().equals(hospitalId)
                                && protocol.projectId().equals(projectId))
                .sorted(java.util.Comparator.comparing(
                        ProtocolData::createdAt))
                .forEach(protocol ->
                        sections.getOrDefault(
                                        protocol.id(), List.of()).stream()
                                .filter(section -> section.sectionCode()
                                        .equals(sectionCode))
                                .forEach(section ->
                                        versions.getOrDefault(
                                                        section.sectionId(),
                                                        List.of())
                                                .forEach(version ->
                                                        result.add(
                                                                new ProjectSectionVersionData(
                                                                        sectionCode,
                                                                        section.title(),
                                                                        protocol.createdAt(),
                                                                        version.versionNo(),
                                                                        version.content(),
                                                                        version.contentFormat(),
                                                                        version.origin(),
                                                                        version.evidenceStatus(),
                                                                        version.changeReason(),
                                                                        version.createdAt())))));
        return List.copyOf(result);
    }

    @Override
    public synchronized Optional<SectionVersionData> appendSectionVersion(
            UUID hospitalId,
            UUID protocolId,
            UUID sectionId,
            int expectedVersionNo,
            String content,
            String origin,
            String changeReason,
            UUID createdBy,
            Instant createdAt) {
        ProtocolData protocol = protocols.get(protocolId);
        if (protocol == null
                || !protocol.hospitalId().equals(hospitalId)) {
            return Optional.empty();
        }
        List<ResearchProtocolModels.ProtocolSection> current =
                new ArrayList<>(sections.getOrDefault(
                        protocolId, List.of()));
        int index = -1;
        for (int position = 0; position < current.size(); position++) {
            if (current.get(position).sectionId().equals(sectionId)) {
                index = position;
                break;
            }
        }
        if (index < 0
                || current.get(index).versionNo() != expectedVersionNo) {
            return Optional.empty();
        }
        var previous = current.get(index);
        if (previous.content().equals(content)) {
            throw new IllegalArgumentException("章节内容没有变化");
        }
        int nextVersion = expectedVersionNo + 1;
        var updated = new ResearchProtocolModels.ProtocolSection(
                previous.sectionId(), previous.sectionCode(),
                previous.title(), previous.sortOrder(), nextVersion,
                content, previous.contentFormat(), origin,
                previous.evidenceStatus(),
                previous.sourceIdentifiers(),
                previous.issuesToConfirm());
        current.set(index, updated);
        sections.put(protocolId, List.copyOf(current));
        SectionVersionData value = new SectionVersionData(
                UUID.randomUUID(), hospitalId, sectionId,
                nextVersion, content, previous.contentFormat(),
                origin, previous.evidenceStatus(),
                write(previous.sourceIdentifiers()),
                write(previous.issuesToConfirm()),
                changeReason, createdBy, createdAt);
        List<SectionVersionData> history = new ArrayList<>(
                versions.getOrDefault(sectionId, List.of()));
        history.add(value);
        versions.put(sectionId, List.copyOf(history));
        return Optional.of(value);
    }

    @Override
    public synchronized boolean updateResultSnapshot(
            UUID hospitalId,
            UUID protocolId,
            String resultJson,
            Instant updatedAt) {
        ProtocolData current = protocols.get(protocolId);
        if (current == null
                || !current.hospitalId().equals(hospitalId)) {
            return false;
        }
        protocols.put(protocolId, new ProtocolData(
                current.id(), current.hospitalId(),
                current.projectId(), current.agentTaskId(),
                current.status(), current.studyType(),
                current.title(), current.schemaVersion(),
                current.generatorVersion(), current.inputSha256(),
                current.issuesToConfirmJson(), resultJson,
                current.createdAt()));
        return true;
    }

    @Override
    public void save(
            ProtocolData protocol,
            List<ResearchProtocolModels.ProtocolSection> sectionValues) {
        if (protocols.putIfAbsent(protocol.id(), protocol) != null) {
            throw new IllegalStateException("研究方案已存在");
        }
        sections.put(protocol.id(), List.copyOf(sectionValues));
        for (var section : sectionValues) {
            versions.put(section.sectionId(), List.of(
                    new SectionVersionData(
                            UUID.randomUUID(), protocol.hospitalId(),
                            section.sectionId(), section.versionNo(),
                            section.content(), section.contentFormat(),
                            section.origin(), section.evidenceStatus(),
                            write(section.sourceIdentifiers()),
                            write(section.issuesToConfirm()),
                            "INITIAL_AGENT_GENERATION", null,
                            protocol.createdAt())));
        }
    }

    List<ProtocolData> all() {
        return List.copyOf(protocols.values());
    }

    List<ResearchProtocolModels.ProtocolSection> sections(UUID protocolId) {
        return sections.getOrDefault(protocolId, List.of());
    }

    private String write(List<String> values) {
        StringBuilder result = new StringBuilder("[");
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) result.append(',');
            result.append('"')
                    .append(values.get(index)
                            .replace("\\", "\\\\")
                            .replace("\"", "\\\""))
                    .append('"');
        }
        return result.append(']').toString();
    }
}
