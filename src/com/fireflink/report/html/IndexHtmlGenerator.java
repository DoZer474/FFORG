package com.fireflink.report.html;

import com.fireflink.report.model.ScriptSummary;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.fireflink.report.html.HtmlUtil.esc;
import static com.fireflink.report.html.HtmlUtil.statusBadge;
import static com.fireflink.report.html.HtmlUtil.statusClass;

public class IndexHtmlGenerator {

    /** A node in the real nested module tree - either has child module nodes, or leaf scripts, or both. */
    private static class ModuleNode {
        String name;
        Map<String, ModuleNode> children = new LinkedHashMap<>();
        List<ScriptSummary> scripts = new ArrayList<>();
    }

    public void generate(String licenseId, String executionId, List<ScriptSummary> scripts, Path outputFile) throws IOException {
        int totalScripts = scripts.size();
        int totalPassed = 0, totalFailed = 0, totalSkipped = 0, totalTerminated = 0, totalSteps = 0;
        for (ScriptSummary s : scripts) {
            totalPassed += s.passed;
            totalFailed += s.failed;
            totalSkipped += s.skipped;
            totalTerminated += s.terminated;
            totalSteps += s.totalSteps;
        }
        int scriptsFailed = (int) scripts.stream().filter(s -> "fail".equals(statusClass(s.overallStatusText))).count();
        int scriptsTerminated = (int) scripts.stream().filter(s -> "terminate".equals(statusClass(s.overallStatusText))).count();
        int scriptsPassed = (int) scripts.stream().filter(s -> "pass".equals(statusClass(s.overallStatusText))).count();

        // Build a REAL nested tree from each script's module path (e.g.
        // "Sapient Migration / Automation / Royal Mail APIs / Create Shipment
        // Scenarios"), splitting on " / " into actual parent/child module
        // nodes - not one flat bucket keyed by the whole path string.
        ModuleNode rootNode = new ModuleNode();
        rootNode.name = null;
        for (ScriptSummary s : scripts) {
            ModuleNode cursor = rootNode;
            if (s.moduleName != null && !s.moduleName.isBlank()) {
                for (String part : s.moduleName.split("\\s*/\\s*")) {
                    if (part.isBlank()) continue;
                    cursor = cursor.children.computeIfAbsent(part, k -> {
                        ModuleNode n = new ModuleNode();
                        n.name = k;
                        return n;
                    });
                }
            }
            cursor.scripts.add(s);
        }
        // scripts with no module path at all land directly on rootNode.scripts;
        // give that bucket a visible label if it's non-empty.
        if (!rootNode.scripts.isEmpty() && rootNode.children.isEmpty() && totalScripts == rootNode.scripts.size()) {
            rootNode.name = "Scripts"; // no module data at all for this whole run
        }

        StringBuilder sb = new StringBuilder();
        sb.append("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\">");
        sb.append("<title>Execution ").append(esc(executionId)).append(" Report</title>");
        sb.append("<link rel=\"stylesheet\" href=\"assets/style.css\"></head><body>");
        sb.append(Assets.LOADER_OVERLAY_HTML);

        sb.append("<header class=\"page-header\">");
        sb.append(Assets.LOGO_HTML);
        sb.append("<h1>Execution Report: ").append(esc(executionId)).append("</h1>");
        sb.append("<div class=\"meta\">License: <code>").append(esc(licenseId)).append("</code></div>");
        sb.append("</header>");

        sb.append("<section class=\"summary-cards\">");
        sb.append(card("Scripts", String.valueOf(totalScripts)));
        sb.append(card("Scripts Passed", String.valueOf(scriptsPassed), "pass"));
        sb.append(card("Scripts Failed", String.valueOf(scriptsFailed), "fail"));
        sb.append(card("Scripts Terminated", String.valueOf(scriptsTerminated), "terminate"));
        sb.append(card("Total Steps", String.valueOf(totalSteps)));
        sb.append("</section>");

        // --- graphical breakdown: donut chart of steps by outcome, pure inline SVG (no JS/CDN needed) ---
        sb.append(buildDonutSection(totalPassed, totalFailed, totalTerminated, totalSkipped));

        sb.append("<main class=\"module-tree\">");
        if (rootNode.name != null) {
            // whole run has no module data - render root's own scripts as one flat bucket
            renderModuleNode(sb, rootNode, 0);
        } else {
            for (ModuleNode child : rootNode.children.values()) {
                renderModuleNode(sb, child, 0);
            }
            if (!rootNode.scripts.isEmpty()) {
                ModuleNode unassigned = new ModuleNode();
                unassigned.name = "Other Scripts";
                unassigned.scripts = rootNode.scripts;
                renderModuleNode(sb, unassigned, 0);
            }
        }
        sb.append("</main>");

        sb.append("</body></html>");

        Files.writeString(outputFile, sb.toString());
    }

    /** Recursively renders a module node: nested <details> for child modules (bold), then a table of leaf scripts (normal weight) if any. */
    private void renderModuleNode(StringBuilder sb, ModuleNode node, int depth) {
        int[] counts = countByStatus(node);
        int totalUnder = countAllScripts(node);

        sb.append("<details class=\"module-block depth-").append(Math.min(depth, 4)).append("\" open>");
        sb.append("<summary class=\"module-head\">");
        sb.append("<span class=\"step-expand-icon\">&#9654;</span>");
        sb.append("<span class=\"module-name\">").append(esc(node.name)).append("</span>");
        sb.append("<span class=\"module-count\">").append(totalUnder).append(" script").append(totalUnder == 1 ? "" : "s").append("</span>");
        sb.append("<span class=\"module-stats\">").append(counts[0]).append(" pass &middot; ")
                .append(counts[1]).append(" fail &middot; ").append(counts[2]).append(" terminated</span>");
        sb.append("</summary>");

        sb.append("<div class=\"module-body\">");
        for (ModuleNode child : node.children.values()) {
            renderModuleNode(sb, child, depth + 1);
        }
        if (!node.scripts.isEmpty()) {
            sb.append("<table class=\"script-table\">");
            sb.append("<tr><th>Status</th><th>Script</th><th>Steps</th><th>Passed</th><th>Failed</th><th>Terminated</th><th>Skipped</th></tr>");
            for (ScriptSummary s : node.scripts) {
                sb.append("<tr class=\"row-status-").append(statusClass(s.overallStatusText)).append("\">");
                sb.append("<td>").append(statusBadge(s.overallStatusText)).append("</td>");
                sb.append("<td><a href=\"").append(esc(s.htmlPath)).append("\">")
                        .append(esc(s.scriptName != null ? s.scriptName : s.scriptId)).append("</a></td>");
                sb.append("<td>").append(s.totalSteps).append("</td>");
                sb.append("<td class=\"num-pass\">").append(s.passed).append("</td>");
                sb.append("<td class=\"num-fail\">").append(s.failed).append("</td>");
                sb.append("<td class=\"num-terminate\">").append(s.terminated).append("</td>");
                sb.append("<td class=\"num-skip\">").append(s.skipped).append("</td>");
                sb.append("</tr>");
            }
            sb.append("</table>");
        }
        sb.append("</div>");
        sb.append("</details>");
    }

    private int countAllScripts(ModuleNode node) {
        int c = node.scripts.size();
        for (ModuleNode child : node.children.values()) c += countAllScripts(child);
        return c;
    }

    /** @return [passed, failed, terminated] counts of scripts anywhere under this node. */
    private int[] countByStatus(ModuleNode node) {
        int[] counts = new int[3];
        for (ScriptSummary s : node.scripts) {
            String cls = statusClass(s.overallStatusText);
            if ("pass".equals(cls)) counts[0]++;
            else if ("fail".equals(cls)) counts[1]++;
            else if ("terminate".equals(cls)) counts[2]++;
        }
        for (ModuleNode child : node.children.values()) {
            int[] c = countByStatus(child);
            counts[0] += c[0]; counts[1] += c[1]; counts[2] += c[2];
        }
        return counts;
    }

    private String card(String label, String value) {
        return card(label, value, null);
    }

    private String card(String label, String value, String tone) {
        String cls = tone != null ? " tone-" + tone : "";
        return "<div class=\"card" + cls + "\"><div class=\"card-value\">" + esc(value) + "</div>"
                + "<div class=\"card-label\">" + esc(label) + "</div></div>";
    }

    /** Renders a pure-SVG donut chart (no JS, no external chart library - works fully offline). */
    private String buildDonutSection(int pass, int fail, int terminate, int skip) {
        int total = pass + fail + terminate + skip;
        if (total == 0) return "";

        String[] labels = {"Passed", "Failed", "Terminated", "Skipped"};
        int[] values = {pass, fail, terminate, skip};
        String[] colors = {"var(--pass)", "var(--fail)", "var(--terminate)", "var(--skip)"};

        double radius = 60;
        double circumference = 2 * Math.PI * radius;
        double cumulative = 0;

        StringBuilder svg = new StringBuilder();
        svg.append("<svg viewBox=\"0 0 160 160\" width=\"160\" height=\"160\" class=\"donut-chart\">");
        svg.append("<g transform=\"translate(80,80) rotate(-90)\">");
        for (int i = 0; i < values.length; i++) {
            if (values[i] == 0) continue;
            double fraction = values[i] / (double) total;
            double dash = fraction * circumference;
            double gap = circumference - dash;
            svg.append("<circle r=\"").append(radius).append("\" cx=\"0\" cy=\"0\" fill=\"transparent\" ")
                    .append("stroke=\"").append(colors[i]).append("\" stroke-width=\"22\" ")
                    .append("stroke-dasharray=\"").append(fmt(dash)).append(" ").append(fmt(gap)).append("\" ")
                    .append("stroke-dashoffset=\"-").append(fmt(cumulative)).append("\" />");
            cumulative += dash;
        }
        svg.append("</g>");
        svg.append("<text x=\"80\" y=\"75\" text-anchor=\"middle\" class=\"donut-center-value\">").append(total).append("</text>");
        svg.append("<text x=\"80\" y=\"93\" text-anchor=\"middle\" class=\"donut-center-label\">total steps</text>");
        svg.append("</svg>");

        StringBuilder legend = new StringBuilder();
        legend.append("<div class=\"chart-legend\">");
        for (int i = 0; i < labels.length; i++) {
            if (values[i] == 0) continue;
            int pct = (int) Math.round(values[i] * 100.0 / total);
            legend.append("<div class=\"chart-legend-row\">")
                    .append("<span class=\"chart-swatch\" style=\"background:").append(colors[i]).append("\"></span>")
                    .append("<span class=\"chart-legend-label\">").append(labels[i]).append("</span>")
                    .append("<span class=\"chart-legend-value\">").append(values[i]).append(" (").append(pct).append("%)</span>")
                    .append("</div>");
        }
        legend.append("</div>");

        return "<section class=\"chart-section\">" + svg + legend + "</section>";
    }

    private String fmt(double d) {
        return String.format(java.util.Locale.ROOT, "%.2f", d);
    }
}
