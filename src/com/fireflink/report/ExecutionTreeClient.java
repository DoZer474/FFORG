package com.fireflink.report;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * Fetches the real module/script name tree from FireFlink's suite-execution
 * tree endpoint:
 *
 *   GET {baseUrl}/alltrees/optimize/v3/tree/execution/suite?sourceId={executionId}&action=expandAll
 *
 * Unlike the previous attempt at this (which targeted an unconfirmed
 * endpoint reverse-engineered from a JS template), this URL and its
 * required headers (licensetype / projectid / projectname / projecttype, in
 * addition to the usual bearer token) were captured directly from a live
 * request, so the ENDPOINT itself is confirmed. What's still unconfirmed is
 * the exact JSON field names in the response body - only a human-rendered
 * tree diagram was available, not raw JSON, so the parser below is a
 * best-effort generic tree walker rather than a hard-coded shape:
 *
 *  - it treats ANY object with a "name"/"title" string and a "children"/
 *    "childList"/"nodes" array as a tree node, regardless of field naming
 *    for the node's type;
 *  - a node is treated as a MODULE (contributes to the bold module path) if
 *    its own id-like field contains "MOD_", or its type-like field contains
 *    "MODULE";
 *  - a node is treated as a SCRIPT leaf if its name (or an id-like field)
 *    matches one of the scriptIds already known from the step-results API
 *    (passed in via knownScriptIds) - this anchors parsing to something we
 *    KNOW is correct even if every other field name guess is wrong.
 *
 * If nothing matches, the raw response is saved to
 * {outputDir}/execution-tree-debug.json so it can be inspected and a real
 * sample shared back for a precise fix, instead of guessing blind again.
 * This class never fails the overall run - any error here just means
 * scripts fall back to showing their scriptId.
 */
public class ExecutionTreeClient {

    private final HttpClient http;
    private final Config config;
    private final AuthManager authManager;
    private final ObjectMapper mapper = new ObjectMapper();

    public ExecutionTreeClient(HttpClient http, Config config, AuthManager authManager) {
        this.http = http;
        this.config = config;
        this.authManager = authManager;
    }

    public static class NameInfo {
        public final Map<String, String> scriptNameById = new HashMap<>();
        /** Full bold-path for the script's ancestor modules, e.g. "Sapient Migration / Automation / UI Test Cases / Login" */
        public final Map<String, String> modulePathByScriptId = new HashMap<>();
    }

    /** @return populated NameInfo, or an empty one if the call/parse fails or project context isn't configured. */
    public NameInfo fetchNames(java.util.Set<String> knownScriptIds) {
        NameInfo result = new NameInfo();

        if (!config.hasProjectContext()) {
            System.out.println("[NAMES] FF_LICENSE_TYPE / FF_PROJECT_ID / FF_PROJECT_NAME / FF_PROJECT_TYPE not all set - " +
                    "skipping module/script name enrichment. Set these to enable it.");
            return result;
        }

        try {
            String url = String.format("%s/alltrees/optimize/v3/tree/execution/suite?sourceId=%s&action=expandAll",
                    config.baseUrl, URLEncoder.encode(config.executionId, StandardCharsets.UTF_8));

            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Authorization", "Bearer " + authManager.getToken())
                    .header("Accept", "*/*")
                    .header("licensetype", config.licenseType)
                    .header("projectid", config.projectId)
                    .header("projectname", config.projectName)
                    .header("projecttype", config.projectType)
                    .GET()
                    .build();

            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 401) {
                authManager.refresh();
                request = HttpRequest.newBuilder()
                        .uri(URI.create(url))
                        .header("Authorization", "Bearer " + authManager.getToken())
                        .header("Accept", "*/*")
                        .header("licensetype", config.licenseType)
                        .header("projectid", config.projectId)
                        .header("projectname", config.projectName)
                        .header("projecttype", config.projectType)
                        .GET()
                        .build();
                response = http.send(request, HttpResponse.BodyHandlers.ofString());
            }

            if (response.statusCode() != 200) {
                System.out.println("[NAMES] Tree endpoint returned HTTP " + response.statusCode()
                        + " - continuing without real script/module names.");
                return result;
            }

            JsonNode root = mapper.readTree(response.body());
            JsonNode data = root.has("responseObject") ? root.get("responseObject") : root;

            walk(data, new ArrayDeque<>(), knownScriptIds, result);

            if (result.scriptNameById.isEmpty()) {
                Path debugFile = Path.of(config.outputDir, "execution-tree-debug.json");
                Files.createDirectories(debugFile.getParent());
                Files.writeString(debugFile, root.toPrettyString());
                System.out.println("[NAMES] Tree fetched OK but no known scriptIds were matched in it. " +
                        "Raw response saved to " + debugFile.toAbsolutePath() + " - share that (redacted) to fix the parser.");
            } else {
                System.out.println("[NAMES] Got real names for " + result.scriptNameById.size() + " scripts.");
            }
        } catch (Exception e) {
            System.out.println("[NAMES] Could not fetch script/module names (" + e.getClass().getSimpleName()
                    + ": " + e.getMessage() + ") - continuing with scriptId as display name.");
        }
        return result;
    }

    private void walk(JsonNode node, Deque<String> modulePath, java.util.Set<String> knownScriptIds, NameInfo result) {
        if (node == null || node.isMissingNode() || node.isNull()) return;

        if (node.isArray()) {
            for (JsonNode child : node) walk(child, modulePath, knownScriptIds, result);
            return;
        }
        if (!node.isObject()) return;

        String name = firstNonBlank(textOf(node, "name"), textOf(node, "title"), textOf(node, "label"));
        String idLike = firstNonBlank(textOf(node, "id"), textOf(node, "nodeId"), textOf(node, "sourceId"), textOf(node, "uniqueId"));
        String typeLike = firstNonBlank(textOf(node, "type"), textOf(node, "nodeType"), textOf(node, "entityType"));

        boolean pushedModule = false;
        boolean isModule = (idLike != null && idLike.contains("MOD_")) || (typeLike != null && typeLike.toUpperCase().contains("MODULE"));
        if (isModule && name != null) {
            modulePath.addLast(name);
            pushedModule = true;
        }

        // a node is a script if its name or id matches a scriptId we already know is real (anchored to confirmed data)
        String matchedScriptId = null;
        if (name != null && knownScriptIds.contains(name)) matchedScriptId = name;
        if (matchedScriptId == null && idLike != null && knownScriptIds.contains(idLike)) matchedScriptId = idLike;
        // also check any string-valued field for an exact scriptId match, in case it's under an unexpected key
        if (matchedScriptId == null) {
            Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
            while (matchedScriptId == null && fields.hasNext()) {
                Map.Entry<String, JsonNode> f = fields.next();
                if (f.getValue().isTextual() && knownScriptIds.contains(f.getValue().asText())) {
                    matchedScriptId = f.getValue().asText();
                }
            }
        }
        if (matchedScriptId != null) {
            if (name != null) result.scriptNameById.put(matchedScriptId, name);
            if (!modulePath.isEmpty()) result.modulePathByScriptId.put(matchedScriptId, String.join(" / ", modulePath));
        }

        // recurse into any array-valued field that looks like a children list
        Iterator<Map.Entry<String, JsonNode>> fields = node.fields();
        List<Map.Entry<String, JsonNode>> toRecurse = new ArrayList<>();
        while (fields.hasNext()) {
            Map.Entry<String, JsonNode> f = fields.next();
            String key = f.getKey().toLowerCase();
            if (f.getValue().isArray() && (key.contains("child") || key.contains("node") || key.equals("machines")
                    || key.equals("results") || key.equals("modules") || key.equals("scripts") || key.equals("testcases"))) {
                toRecurse.add(f);
            } else if (f.getValue().isObject()) {
                toRecurse.add(f);
            }
        }
        for (Map.Entry<String, JsonNode> f : toRecurse) {
            walk(f.getValue(), modulePath, knownScriptIds, result);
        }

        if (pushedModule) modulePath.removeLast();
    }

    private String textOf(JsonNode node, String field) {
        JsonNode v = node.get(field);
        return (v != null && v.isTextual()) ? v.asText() : null;
    }

    private String firstNonBlank(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank()) return v;
        }
        return null;
    }
}
