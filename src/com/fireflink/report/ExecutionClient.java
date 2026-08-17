package com.fireflink.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireflink.report.model.ApiEnvelope;
import com.fireflink.report.model.ScriptInfo;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;

/**
 * Calls GET /project/optimize/v3/customapi/scriptid-runid/{licenseId}/{executionId}
 * to get the full list of scripts (and their runIds) in an execution.
 */
public class ExecutionClient {

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Config config;
    private final AuthManager authManager;

    public ExecutionClient(HttpClient http, Config config, AuthManager authManager) {
        this.http = http;
        this.config = config;
        this.authManager = authManager;
    }

    public List<ScriptInfo> getScripts() throws Exception {
        String url = String.format("%s/project/optimize/v3/customapi/scriptid-runid/%s/%s",
                config.baseUrl, config.licenseId, config.executionId);

        HttpResponse<String> response = sendWithAuth(url);

        if (response.statusCode() == 401) {
            authManager.refresh();
            response = sendWithAuth(url);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("Fetching script list failed: HTTP " + response.statusCode()
                    + " - " + response.body());
        }

        // CONFIRMED: responseObject is a flat array of strings, each shaped
        // "{scriptId}-{runId}", e.g. "SCR58996-Run2772d144-d2c1-42b1-aa1d-8b8a0f61bb33".
        // The runId itself contains hyphens, but the scriptId does not, so
        // splitting on the FIRST hyphen only correctly separates the two.
        var listType = mapper.getTypeFactory().constructCollectionType(List.class, String.class);
        var envelopeType = mapper.getTypeFactory().constructParametricType(ApiEnvelope.class, listType);
        ApiEnvelope<List<String>> envelope = mapper.readValue(response.body(), envelopeType);

        if (envelope.responseObject == null) {
            throw new RuntimeException("Script list response had no responseObject. Raw: " + response.body());
        }

        List<ScriptInfo> scripts = new java.util.ArrayList<>();
        for (String entry : envelope.responseObject) {
            String[] parts = entry.split("-", 2);
            if (parts.length != 2) {
                throw new RuntimeException("Unexpected scriptId-runId entry format: '" + entry + "'");
            }
            ScriptInfo info = new ScriptInfo();
            info.scriptId = parts[0];
            info.runId = parts[1];
            // no name/module/status available from this endpoint - scriptId
            // stands in as the display name until another endpoint supplies one.
            info.scriptName = parts[0];
            scripts.add(info);
        }
        return scripts;
    }

    private HttpResponse<String> sendWithAuth(String url) throws java.io.IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authManager.getToken())
                .header("Accept", "*/*")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
