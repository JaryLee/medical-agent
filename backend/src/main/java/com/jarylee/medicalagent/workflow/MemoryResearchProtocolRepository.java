package com.jarylee.medicalagent.workflow;

import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryResearchProtocolRepository implements ResearchProtocolRepository {
    private final Map<UUID, ProtocolData> protocols = new ConcurrentHashMap<>();
    private final Map<UUID, List<ResearchProtocolModels.ProtocolSection>> sections =
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
    public void save(
            ProtocolData protocol,
            List<ResearchProtocolModels.ProtocolSection> sectionValues) {
        if (protocols.putIfAbsent(protocol.id(), protocol) != null) {
            throw new IllegalStateException("研究方案已存在");
        }
        sections.put(protocol.id(), List.copyOf(sectionValues));
    }

    List<ProtocolData> all() {
        return List.copyOf(protocols.values());
    }

    List<ResearchProtocolModels.ProtocolSection> sections(UUID protocolId) {
        return sections.getOrDefault(protocolId, List.of());
    }
}
