package com.jarylee.medicalagent.literature;

import com.jarylee.medicalagent.agent.model.ResearchModels.LiteratureRecord;
import java.util.List;

public interface LiteratureGateway {
    List<LiteratureRecord> search(String query);
}
