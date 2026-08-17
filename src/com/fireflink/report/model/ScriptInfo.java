package com.fireflink.report.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Populated by parsing the "{scriptId}-{runId}" strings returned by
 * /customapi/scriptid-runid/{lic}/{exec} (CONFIRMED format from a live run).
 * That endpoint provides no script name, module name, or status - only the
 * id/runId pairing - so scriptName currently defaults to scriptId. If a
 * separate endpoint providing names turns up later, wire it in here.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class ScriptInfo {
    public String scriptId;
    public String runId;
    public String scriptName;
    public String moduleName;
    public String status;
}
