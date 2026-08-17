package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StepResultsPage {
    public List<StepResult> stepResults;
    public int totalRecords;
    public int pageNo;
    public int totalPages;
    public int pageSize;
}
