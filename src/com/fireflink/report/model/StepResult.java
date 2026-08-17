package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors one element of responseObject.stepResults[] from the
 * /customapi/step-results endpoint. Only fields the report actually renders
 * are declared; everything else is ignored during deserialization.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class StepResult {
    public String id;
    public String parentId;
    public String originalParentId;
    public String name;
    public String type;          // "step", "Group", "StartCondition", ...
    public String entityType;    // "STEP"
    public String stepSection;   // "step"
    public String stepType;      // "Condition", "StepGroup", null for plain steps
    public int hierarchy;
    public String status;        // PASS / FAIL / SKIP / WARNING / ...
    public String message;
    public String toolTip;
    public String nlpName;
    public String stepId;
    public String uniqueId;
    public String executedOn;
    public long executionDuration;
    public String executionDurationInHourMinSecFormat;
    public boolean isScreenshotNotApplicable;
    public boolean container;
    public boolean skip;
    public int displayOrder;
    public String sequence;

    public List<StepInput> stepInputs = new ArrayList<>();
    public StepReturn stepReturn;
    public StepResultStats stepResultStats; // present on container/group rows

    // Present on API/workbench steps only (name usually starts with "API
    // Request:"). Deliberately typed as a generic JsonNode rather than a
    // strict POJO: this is a doubly JSON-stringified structure
    // (workbenchExecutionResult.request is a JSON string containing another
    // JSON string containing the actual HTTP request spec; .response is a
    // nested object with statusCode/headers/responseBody/assertions) whose
    // exact shape isn't worth hard-coding into Java. It's parsed defensively
    // in JS at render time instead (see ScriptHtmlGenerator's embedded app),
    // so any schema variation across step types degrades gracefully instead
    // of breaking the whole pipeline.
    public com.fasterxml.jackson.databind.JsonNode workbenchExecutionResult;

    // Populated after load by StepTreeBuilder (empty at raw-fetch time, since
    // the flat API responses don't nest children). Deliberately NOT marked
    // transient: ScriptHtmlGenerator serializes the built tree straight into
    // the page's embedded JSON, and needs children included at that point.
    public List<StepResult> children = new ArrayList<>();

    public boolean isContainer() {
        return container;
    }
}
