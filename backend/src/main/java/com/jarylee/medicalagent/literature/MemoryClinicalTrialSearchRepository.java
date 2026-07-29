package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.literature.LiteratureSearchRepository.SearchData;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Repository
@Profile("memory")
public class MemoryClinicalTrialSearchRepository implements ClinicalTrialSearchRepository {
    private final Map<UUID, SearchData> searches = new ConcurrentHashMap<>();
    private final Map<UUID, List<ClinicalTrialsSearchModels.Trial>> trials =
            new ConcurrentHashMap<>();

    @Override
    public void create(SearchData search) {
        if (searches.putIfAbsent(search.id(), search) != null) {
            throw new IllegalStateException("临床试验检索记录已存在");
        }
    }

    @Override
    public void complete(SearchData search, List<ClinicalTrialsSearchModels.Trial> values) {
        searches.compute(search.id(), (id, current) -> {
            if (current == null || !"RUNNING".equals(current.status())) {
                throw new IllegalStateException("临床试验检索记录当前不可完成");
            }
            return search;
        });
        trials.put(search.id(), List.copyOf(values));
    }

    @Override
    public void fail(UUID hospitalId, UUID searchId, String errorCode,
                     String errorMessage, Instant completedAt) {
        searches.computeIfPresent(searchId, (id, current) -> new SearchData(
                current.id(), current.hospitalId(), current.projectId(), current.agentTaskId(),
                current.database(), current.originalQuestion(), current.structuredConceptsJson(),
                current.query(), current.queryVersion(), current.filtersJson(), "FAILED",
                current.startedAt(), completedAt, null, null, null, null, null,
                null, null, errorCode, truncate(errorMessage)));
    }

    List<SearchData> all() {
        return new ArrayList<>(searches.values());
    }

    private String truncate(String value) {
        if (value == null) return "未知错误";
        return value.length() <= 500 ? value : value.substring(0, 500);
    }
}
