package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public class StepInput {
    public String name;
    public String value;
    public String type;
    public String reference;
    public String actualValue;
    public boolean parameter;
}
