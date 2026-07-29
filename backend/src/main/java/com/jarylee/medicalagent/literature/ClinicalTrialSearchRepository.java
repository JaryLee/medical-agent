package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.literature.LiteratureSearchRepository.SearchData;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface ClinicalTrialSearchRepository {
    void create(SearchData search);
    void complete(SearchData search, List<ClinicalTrialsSearchModels.Trial> trials);
    void fail(UUID hospitalId, UUID searchId, String errorCode,
              String errorMessage, Instant completedAt);
}
