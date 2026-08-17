package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StepResultStats {
    public int total;
    public int totalPassed;
    public int totalFailed;
    public int totalWarning;
    public int totalSkipped;
    public int totalTerminated;
    public int totalAborted;
    public int totalNA;
}
