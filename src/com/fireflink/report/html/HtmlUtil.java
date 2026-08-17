package com.fireflink.report.html;

public class HtmlUtil {

    public static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    /** Normalizes any status string to a badge class suffix. */
    public static String statusClass(String status) {
        if (status == null) return "unknown";
        String s = status.toUpperCase();
        if (s.contains("TERMINAT")) return "terminate";
        if (s.contains("ABORT")) return "terminate";
        if (s.startsWith("PASS")) return "pass";
        if (s.startsWith("FAIL")) return "fail";
        if (s.contains("WARN")) return "warn";
        if (s.contains("SKIP")) return "skip";
        return "unknown";
    }

    public static String statusText(String status) {
        String cls = statusClass(status);
        switch (cls) {
            case "pass": return "Passed";
            case "fail": return "Failed";
            case "warn": return "Warning";
            case "skip": return "Skipped";
            case "terminate": return status == null ? "Terminated" : capitalize(status);
            default: return status == null ? "N/A" : status;
        }
    }

    private static String capitalize(String s) {
        if (s.isBlank()) return s;
        return s.substring(0, 1).toUpperCase() + s.substring(1).toLowerCase();
    }

    public static String statusBadge(String status) {
        return "<span class=\"status-badge status-" + statusClass(status) + "\">" + esc(statusText(status)) + "</span>";
    }

    /** Higher = worse outcome. Used to pick the "worst" status among a set (e.g. a script's root-level containers). */
    public static int severity(String status) {
        String cls = statusClass(status);
        switch (cls) {
            case "terminate": return 5;
            case "fail": return 4;
            case "warn": return 3;
            case "skip": return 2;
            case "pass": return 1;
            default: return 0;
        }
    }
}
