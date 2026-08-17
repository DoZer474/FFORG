package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StepReturn {
    public String name;
    public String type;
    public String value;
    public boolean masked;
    public String referenceId;
}
