package com.fireflink.report.web;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * A small local web app wrapping the existing report-generation pipeline:
 * open http://127.0.0.1:8080 in a browser, fill in the same fields you'd
 * otherwise set via set-env.ps1, click Generate, watch live logs, then
 * download a zip of the finished report.
 *
 * Bound to the LOOPBACK address only (127.0.0.1) - this is never reachable
 * from the network, only from this machine. It runs the already-compiled
 * com.fireflink.report.Main as a subprocess with the form values passed as
 * that subprocess's environment variables (never the server's own env, and
 * never written to disk), so credentials only ever live in memory for the
 * duration of that one run.
 *
 * Requires no new dependencies - uses only com.sun.net.httpserver, which
 * ships with the JDK.
 *
 * Run (after the usual javac step that compiles the whole project into bin/):
 *   java -cp "bin;lib\*" com.fireflink.report.web.ReportServer
 * then open http://127.0.0.1:8080
 */
public class ReportServer {

    private static final int PORT = 8080;
    private static volatile Job currentJob = null;

    public static void main(String[] args) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), PORT), 0);
        server.createContext("/", ReportServer::handleIndex);
        server.createContext("/generate", ReportServer::handleGenerate);
        server.createContext("/logs", ReportServer::handleLogs);
        server.createContext("/download", ReportServer::handleDownload);
        server.setExecutor(Executors.newCachedThreadPool());
        server.start();
        System.out.println("FireFlink Report Generator running at http://127.0.0.1:" + PORT);
        System.out.println("Open that URL in your browser. This only listens on localhost - nothing here is reachable from the network.");
        System.out.println("Press Ctrl+C to stop.");
    }

    private static void handleIndex(HttpExchange ex) throws IOException {
        if (!"GET".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }
        byte[] body = LauncherHtml.PAGE.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "text/html; charset=utf-8");
        ex.sendResponseHeaders(200, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    private static void handleGenerate(HttpExchange ex) throws IOException {
        if (!"POST".equals(ex.getRequestMethod())) { ex.sendResponseHeaders(405, -1); return; }

        if (currentJob != null && currentJob.isRunning()) {
            respondJson(ex, 409, "{\"error\":\"A generation is already running. Wait for it to finish.\"}");
            return;
        }

        Map<String, String> form = parseFormUrlEncoded(readBody(ex));
        Job job = new Job(form);
        currentJob = job;
        job.start();

        respondJson(ex, 202, "{\"status\":\"started\"}");
    }

    private static void handleLogs(HttpExchange ex) throws IOException {
        if (currentJob == null) { ex.sendResponseHeaders(404, -1); return; }
        Job job = currentJob;

        ex.getResponseHeaders().add("Content-Type", "text/event-stream");
        ex.getResponseHeaders().add("Cache-Control", "no-cache");
        ex.sendResponseHeaders(200, 0);
        OutputStream os = ex.getResponseBody();

        int sent = 0;
        try {
            while (true) {
                List<String> lines = job.linesSince(sent);
                for (String line : lines) {
                    writeSse(os, "log", line);
                    sent++;
                }
                if (!job.isRunning()) {
                    writeSse(os, "done", String.valueOf(job.exitCode()));
                    break;
                }
                Thread.sleep(300);
            }
        } catch (InterruptedException ignored) {
        } finally {
            os.close();
        }
    }

    private static void handleDownload(HttpExchange ex) throws IOException {
        if (currentJob == null || currentJob.isRunning() || currentJob.exitCode() != 0) {
            ex.sendResponseHeaders(404, -1);
            return;
        }
        Path zipPath = currentJob.buildZipIfNeeded();
        if (zipPath == null || !Files.exists(zipPath)) {
            ex.sendResponseHeaders(500, -1);
            return;
        }
        ex.getResponseHeaders().add("Content-Type", "application/zip");
        ex.getResponseHeaders().add("Content-Disposition", "attachment; filename=\"" + zipPath.getFileName() + "\"");
        long size = Files.size(zipPath);
        ex.sendResponseHeaders(200, size);
        try (OutputStream os = ex.getResponseBody(); InputStream is = Files.newInputStream(zipPath)) {
            is.transferTo(os);
        }
    }

    // ---------- helpers ----------

    private static void writeSse(OutputStream os, String event, String data) throws IOException {
        StringBuilder sb = new StringBuilder();
        sb.append("event: ").append(event).append("\n");
        for (String l : data.split("\n", -1)) {
            sb.append("data: ").append(l).append("\n");
        }
        sb.append("\n");
        os.write(sb.toString().getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    private static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private static Map<String, String> parseFormUrlEncoded(String body) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String pair : body.split("&")) {
            if (pair.isBlank()) continue;
            String[] kv = pair.split("=", 2);
            String key = java.net.URLDecoder.decode(kv[0], StandardCharsets.UTF_8);
            String val = kv.length > 1 ? java.net.URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "";
            map.put(key, val);
        }
        return map;
    }

    private static void respondJson(HttpExchange ex, int status, String json) throws IOException {
        byte[] body = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(status, body.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(body); }
    }

    // ---------- job ----------

    static class Job {
        private final Map<String, String> form;
        private final List<String> log = Collections.synchronizedList(new ArrayList<>());
        private volatile Process process;
        private volatile boolean running = true;
        private volatile int exitCode = -1;
        private String executionId;
        private String outputDir;

        Job(Map<String, String> form) { this.form = form; }

        void start() {
            Thread t = new Thread(this::run, "ffreport-job");
            t.setDaemon(true);
            t.start();
        }

        private void run() {
            try {
                executionId = form.getOrDefault("FF_EXECUTION_ID", "").trim();
                outputDir = form.getOrDefault("FF_OUTPUT_DIR", "").trim();
                if (outputDir.isEmpty()) outputDir = "./reports"; // this is the default the user asked for

                String javaHome = System.getProperty("java.home");
                String javaBin = javaHome + File.separator + "bin" + File.separator + "java";

                List<String> cmd = new ArrayList<>();
                cmd.add(javaBin);
                cmd.add("-Xmx2g");
                cmd.add("-cp");
                cmd.add("bin" + File.pathSeparator + "lib" + File.separator + "*");
                cmd.add("com.fireflink.report.Main");

                ProcessBuilder pb = new ProcessBuilder(cmd);
                pb.redirectErrorStream(true);
                Map<String, String> env = pb.environment();
                for (String key : new String[]{"FF_EMAIL", "FF_PASSWORD", "FF_LICENSE_ID", "FF_EXECUTION_ID",
                        "FF_OUTPUT_DIR", "FF_BASE_URL", "FF_LICENSE_TYPE", "FF_PROJECT_ID", "FF_PROJECT_NAME",
                        "FF_PROJECT_TYPE", "FF_INSECURE_SSL"}) {
                    String v = form.get(key);
                    if (v != null && !v.isBlank()) env.put(key, v);
                    else env.remove(key);
                }
                env.put("FF_OUTPUT_DIR", outputDir);

                log.add("Starting generator (executionId=" + executionId + ", outputDir=" + outputDir + ")...");
                process = pb.start();

                try (BufferedReader r = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        log.add(line);
                    }
                }
                exitCode = process.waitFor();
                log.add(exitCode == 0 ? "Done. Exit code 0." : "Process exited with code " + exitCode + ".");
            } catch (Exception e) {
                log.add("ERROR: " + e.getClass().getSimpleName() + ": " + e.getMessage());
                exitCode = -1;
            } finally {
                running = false;
            }
        }

        boolean isRunning() { return running; }
        int exitCode() { return exitCode; }

        List<String> linesSince(int fromIndex) {
            synchronized (log) {
                if (fromIndex >= log.size()) return List.of();
                return new ArrayList<>(log.subList(fromIndex, log.size()));
            }
        }

        /** Zips {outputDir}/{executionId} into {outputDir}/{executionId}.zip (built once per run, reused on repeat downloads). */
        Path buildZipIfNeeded() throws IOException {
            Path execRoot = Path.of(outputDir, executionId);
            Path zipPath = Path.of(outputDir, executionId + ".zip");
            if (!Files.exists(execRoot)) return null;
            if (Files.exists(zipPath)) return zipPath;

            System.out.println("Zipping " + execRoot.toAbsolutePath() + " for download...");
            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(zipPath));
                 Stream<Path> walk = Files.walk(execRoot)) {
                walk.filter(Files::isRegularFile).forEach(file -> {
                    try {
                        String rel = execRoot.getParent().relativize(file).toString().replace('\\', '/');
                        zos.putNextEntry(new ZipEntry(rel));
                        Files.copy(file, zos);
                        zos.closeEntry();
                    } catch (IOException e) {
                        throw new UncheckedIOException(e);
                    }
                });
            } catch (UncheckedIOException e) {
                throw e.getCause();
            }
            System.out.println("Zip ready: " + zipPath.toAbsolutePath());
            return zipPath;
        }
    }
}
