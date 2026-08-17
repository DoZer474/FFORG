package com.fireflink.report.model;

public class ScriptSummary {
    public String scriptId;
    public String scriptName;
    public String moduleName;
    public int totalSteps;
    public int passed;
    public int failed;
    public int skipped;
    public int terminated;
    public String htmlPath; // relative path from index.html, e.g. "scripts/SCR58996/SCR58996.html"

    /** The real overall status text as FireFlink itself assigned it (e.g. "TERMINATED"),
     *  taken from the worst-severity root-level step - not derived by us from leaf counts. */
    public String overallStatusText;
}
