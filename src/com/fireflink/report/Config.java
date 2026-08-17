package com.fireflink.report;

/**
 * Reads all runtime configuration from environment variables so credentials
 * never appear on the command line or in shell history.
 *
 * Expected env vars (set these via set-env.ps1 before running):
 *   FF_EMAIL          - login email
 *   FF_PASSWORD       - login password
 *   FF_LICENSE_ID     - e.g. LIC25384
 *   FF_EXECUTION_ID   - e.g. EXEC21189
 *   FF_BASE_URL       - e.g. https://us-app.fireflink.com   (optional, has default)
 *   FF_OUTPUT_DIR     - where the offline report folder is written (optional, has default)
 *
 * Optional, only needed for the module/script-tree name enrichment
 * (GET /alltrees/optimize/v3/tree/execution/suite):
 *   FF_LICENSE_TYPE   - e.g. C-Professional
 *   FF_PROJECT_ID     - e.g. PJT1005
 *   FF_PROJECT_NAME   - e.g. "Sapient Migration"
 *   FF_PROJECT_TYPE   - e.g. Web
 * If any of these are unset, name/module enrichment is skipped gracefully
 * (scripts fall back to showing their scriptId) rather than failing the run.
 */
public class Config {

    public final String email;
    public final String password;
    public final String licenseId;
    public final String executionId;
    public final String baseUrl;
    public final String outputDir;
    public final String licenseType;
    public final String projectId;
    public final String projectName;
    public final String projectType;

    private Config(String email, String password, String licenseId,
                    String executionId, String baseUrl, String outputDir,
                    String licenseType, String projectId, String projectName, String projectType) {
        this.email = email;
        this.password = password;
        this.licenseId = licenseId;
        this.executionId = executionId;
        this.baseUrl = baseUrl;
        this.outputDir = outputDir;
        this.licenseType = licenseType;
        this.projectId = projectId;
        this.projectName = projectName;
        this.projectType = projectType;
    }

    public static Config fromEnv() {
        String email = require("FF_EMAIL");
        String password = require("FF_PASSWORD");
        String licenseId = require("FF_LICENSE_ID");
        String executionId = require("FF_EXECUTION_ID");
        String baseUrl = System.getenv().getOrDefault("FF_BASE_URL", "https://us-app.fireflink.com");
        String outputDir = System.getenv().getOrDefault("FF_OUTPUT_DIR", "./reports");
        String licenseType = System.getenv("FF_LICENSE_TYPE");
        String projectId = System.getenv("FF_PROJECT_ID");
        String projectName = System.getenv("FF_PROJECT_NAME");
        String projectType = System.getenv("FF_PROJECT_TYPE");
        return new Config(email, password, licenseId, executionId, baseUrl, outputDir,
                licenseType, projectId, projectName, projectType);
    }

    /** True only if all four project-context env vars are set - required for tree enrichment. */
    public boolean hasProjectContext() {
        return notBlank(licenseType) && notBlank(projectId) && notBlank(projectName) && notBlank(projectType);
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }

    private static String require(String key) {
        String v = System.getenv(key);
        if (v == null || v.isBlank()) {
            throw new IllegalStateException("Missing required environment variable: " + key
                    + "  (set it in set-env.ps1 before running)");
        }
        return v;
    }
}
