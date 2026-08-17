package com.fireflink.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireflink.report.html.Assets;
import com.fireflink.report.html.IndexHtmlGenerator;
import com.fireflink.report.html.ScriptHtmlGenerator;
import com.fireflink.report.model.ScriptInfo;
import com.fireflink.report.model.ScriptSummary;
import com.fireflink.report.model.StepResult;
import com.fireflink.report.tree.StepTreeBuilder;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Single-run pipeline:
 *   1. Sign in (AuthManager keeps the token fresh - re-authenticates
 *      automatically on a 401 anywhere in the run, no manual restart needed).
 *   2. Get the list of scripts for the execution.
 *   3. For each script: page through step-results, stream to
 *        scripts/{scriptId}/{scriptId}.json
 *      then read that file back, build the tree, and render one
 *      self-contained page:
 *        scripts/{scriptId}/{scriptId}.html
 *      (the whole step tree is embedded as JSON inside that one file; the
 *      page renders it lazily level-by-level with a drill-down list and a
 *      slide-over detail panel, so DOM size stays bounded regardless of
 *      total step count - see ScriptHtmlGenerator).
 *   4. Write assets/style.css once.
 *   5. Render index.html dashboard linking every script's first page.
 *
 * Run with env vars set (FF_EMAIL, FF_PASSWORD, FF_LICENSE_ID,
 * FF_EXECUTION_ID, optionally FF_BASE_URL / FF_OUTPUT_DIR / FF_INSECURE_SSL).
 */
public class Main {

    public static void main(String[] args) throws Exception {
        Config config = Config.fromEnv();
        HttpClient http = HttpClientFactory.build();
        ObjectMapper mapper = new ObjectMapper();

        System.out.println("Signing in...");
        AuthClient authClient = new AuthClient(http, config);
        AuthManager authManager = new AuthManager(authClient);
        System.out.println("Signed in OK.");

        System.out.println("Fetching script list for " + config.licenseId + " / " + config.executionId + "...");
        ExecutionClient executionClient = new ExecutionClient(http, config, authManager);
        List<ScriptInfo> scripts = executionClient.getScripts();
        System.out.println("Found " + scripts.size() + " scripts.");

        System.out.println("Attempting to enrich with real script/module names...");
        ExecutionTreeClient treeClient = new ExecutionTreeClient(http, config, authManager);
        java.util.Set<String> knownScriptIds = new java.util.HashSet<>();
        for (ScriptInfo s : scripts) knownScriptIds.add(s.scriptId);
        ExecutionTreeClient.NameInfo names = treeClient.fetchNames(knownScriptIds);
        for (ScriptInfo s : scripts) {
            String realName = names.scriptNameById.get(s.scriptId);
            if (realName != null) s.scriptName = realName;
            String modulePath = names.modulePathByScriptId.get(s.scriptId);
            if (modulePath != null) s.moduleName = modulePath;
        }

        Path executionRoot = Path.of(config.outputDir, config.executionId);
        Path scriptsRoot = executionRoot.resolve("scripts");
        Path assetsRoot = executionRoot.resolve("assets");
        Files.createDirectories(scriptsRoot);
        Files.createDirectories(assetsRoot);

        // write CSS once
        Files.writeString(assetsRoot.resolve("style.css"), Assets.STYLE_CSS);

        StepClient stepClient = new StepClient(http, config, authManager);
        ScriptHtmlGenerator scriptHtmlGenerator = new ScriptHtmlGenerator();

        List<ScriptSummary> summaries = new ArrayList<>();

        for (int i = 0; i < scripts.size(); i++) {
            ScriptInfo script = scripts.get(i);
            System.out.printf("[%d/%d] %s (%s)...%n", i + 1, scripts.size(), script.scriptId, script.scriptName);

            Path scriptFolder = scriptsRoot.resolve(script.scriptId);
            Files.createDirectories(scriptFolder);
            Path jsonFile = scriptFolder.resolve(script.scriptId + ".json");

            if (Files.exists(jsonFile)) {
                System.out.println("    JSON already present, skipping fetch (resume).");
            } else {
                int total = stepClient.fetchAllStepsToFile(script.scriptId, script.runId, jsonFile);
                System.out.println("    Fetched " + total + " step records.");
            }

            List<StepResult> allSteps = mapper.readValue(jsonFile.toFile(),
                    mapper.getTypeFactory().constructCollectionType(List.class, StepResult.class));

            List<StepResult> tree = StepTreeBuilder.buildTree(allSteps, script.scriptId);

            String prevScriptHtml = (i > 0)
                    ? "../" + scripts.get(i - 1).scriptId + "/" + scripts.get(i - 1).scriptId + ".html"
                    : null;
            String nextScriptHtml = (i < scripts.size() - 1)
                    ? "../" + scripts.get(i + 1).scriptId + "/" + scripts.get(i + 1).scriptId + ".html"
                    : null;

            Path htmlFile = scriptFolder.resolve(script.scriptId + ".html");
            scriptHtmlGenerator.generate(script, tree, prevScriptHtml, nextScriptHtml, htmlFile);

            String htmlPath = "scripts/" + script.scriptId + "/" + script.scriptId + ".html";
            summaries.add(buildSummary(script, allSteps, tree, htmlPath));
        }

        System.out.println("Writing dashboard...");
        IndexHtmlGenerator indexGen = new IndexHtmlGenerator();
        indexGen.generate(config.licenseId, config.executionId, summaries, executionRoot.resolve("index.html"));

        System.out.println("Done. Open: " + executionRoot.resolve("index.html").toAbsolutePath());
    }

    private static ScriptSummary buildSummary(ScriptInfo script, List<StepResult> allSteps, List<StepResult> tree, String htmlPath) {
        ScriptSummary summary = new ScriptSummary();
        summary.scriptId = script.scriptId;
        summary.scriptName = script.scriptName;
        summary.moduleName = script.moduleName;
        summary.htmlPath = htmlPath;

        // count only non-container (leaf) steps to avoid double-counting rollups
        for (StepResult s : allSteps) {
            if (s.container) continue;
            summary.totalSteps++;
            String cls = com.fireflink.report.html.HtmlUtil.statusClass(s.status);
            switch (cls) {
                case "pass": summary.passed++; break;
                case "fail": summary.failed++; break;
                case "skip": summary.skipped++; break;
                case "terminate": summary.terminated++; break;
                default: break; // warn/unknown not separately tallied on the dashboard
            }
        }

        // real overall status: the worst-severity status among the TOP-LEVEL
        // containers (e.g. "Pre Conditions", "Steps", "Post Conditions") -
        // this is FireFlink's own assigned rollup, not something we compute
        // by guessing from leaf counts.
        String worst = null;
        int worstSeverity = -1;
        for (StepResult root : tree) {
            int sev = com.fireflink.report.html.HtmlUtil.severity(root.status);
            if (sev > worstSeverity) {
                worstSeverity = sev;
                worst = root.status;
            }
        }
        if (worst == null) {
            // no root-level status available - fall back to leaf-count heuristic
            if (summary.terminated > 0) worst = "TERMINATED";
            else if (summary.failed > 0) worst = "FAIL";
            else if (summary.skipped > 0 && summary.passed == 0) worst = "SKIP";
            else worst = "PASS";
        }
        summary.overallStatusText = worst;

        return summary;
    }
}
