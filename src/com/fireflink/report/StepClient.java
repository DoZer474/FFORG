package com.fireflink.report;

import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireflink.report.model.ApiEnvelope;
import com.fireflink.report.model.StepResult;
import com.fireflink.report.model.StepResultsPage;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Calls GET /project/optimize/v3/customapi/step-results for a script, one
 * page at a time, and streams each page straight to disk as it arrives.
 * Only one page's worth of StepResult objects is ever held in memory at
 * once, regardless of whether the script has 100 steps or 58,000.
 */
public class StepClient {

    private static final int PAGE_SIZE = 1000;

    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();
    private final Config config;
    private final AuthManager authManager;

    public StepClient(HttpClient http, Config config, AuthManager authManager) {
        this.http = http;
        this.config = config;
        this.authManager = authManager;
    }

    /**
     * Fetches every page for (scriptId, runId) and writes the combined step
     * list as a single JSON array to outputFile. Returns the total record
     * count reported by the API (for sanity-checking / progress logging).
     *
     * If outputFile already exists and ends with a valid closing marker,
     * the caller should have already skipped this script (resume support) -
     * this method always does a full (re)fetch when called.
     */
    public int fetchAllStepsToFile(String scriptId, String runId, Path outputFile) throws Exception {
        Files.createDirectories(outputFile.getParent());

        int pageNo = 1;
        int totalPages = 1; // updated after first page
        int totalRecords = 0;

        try (var out = Files.newBufferedWriter(outputFile);
             JsonGenerator gen = mapper.getFactory().createGenerator(out)) {

            gen.writeStartArray();

            do {
                StepResultsPage page = fetchPage(scriptId, runId, pageNo);
                totalPages = page.totalPages;
                totalRecords = page.totalRecords;

                for (StepResult step : page.stepResults) {
                    mapper.writeValue(gen, step);
                }

                pageNo++;
            } while (pageNo <= totalPages);

            gen.writeEndArray();
        }

        return totalRecords;
    }

    private StepResultsPage fetchPage(String scriptId, String runId, int pageNo) throws IOException, InterruptedException {
        String url = String.format("%s/project/optimize/v3/customapi/step-results?scriptId=%s&runId=%s&pageNo=%d&pageSize=%d",
                config.baseUrl, scriptId, runId, pageNo, PAGE_SIZE);

        HttpResponse<String> response = sendWithAuth(url);

        if (response.statusCode() == 401) {
            // token likely expired mid-run - re-authenticate once and retry this exact page
            try {
                authManager.refresh();
            } catch (Exception e) {
                throw new RuntimeException("Re-authentication failed after 401 on page " + pageNo + " for " + scriptId, e);
            }
            response = sendWithAuth(url);
        }

        if (response.statusCode() != 200) {
            throw new RuntimeException("step-results page " + pageNo + " for " + scriptId
                    + " failed: HTTP " + response.statusCode() + " - " + response.body());
        }

        var envelopeType = mapper.getTypeFactory()
                .constructParametricType(ApiEnvelope.class, StepResultsPage.class);
        ApiEnvelope<StepResultsPage> envelope = mapper.readValue(response.body(), envelopeType);

        if (envelope.responseObject == null) {
            throw new RuntimeException("step-results page " + pageNo + " for " + scriptId
                    + " had no responseObject. Raw: " + response.body());
        }
        return envelope.responseObject;
    }

    private HttpResponse<String> sendWithAuth(String url) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + authManager.getToken())
                .header("Accept", "*/*")
                .GET()
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }
}
