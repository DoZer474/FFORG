package com.fireflink.report.web;

/** The single-page form + live-log UI served at http://127.0.0.1:8080. */
public class LauncherHtml {

    public static final String PAGE = "<!DOCTYPE html>\n" +
        "<html lang=\"en\"><head><meta charset=\"UTF-8\">\n" +
        "<title>FireFlink Offline Report Generator</title>\n" +
        "<style>\n" +
        "  :root{--primary:#5C2D91;--primary-light:#7A45C4;--primary-dark:#3E1D66;--bg:#f4f2fa;--card:#fff;--border:#e2e8f0;--text:#0f172a;--muted:#64748b;}\n" +
        "  *{box-sizing:border-box;}\n" +
        "  body{font-family:'Segoe UI',Inter,-apple-system,Roboto,Arial,sans-serif;background:var(--bg);color:var(--text);margin:0;padding:0 24px 48px;}\n" +
        "  header{background:linear-gradient(135deg,var(--primary) 0%,var(--primary-light) 100%);color:#fff;margin:0 -24px 24px;padding:20px 24px;border-radius:0 0 16px 16px;}\n" +
        "  header h1{margin:0;font-size:20px;}\n" +
        "  header p{margin:4px 0 0;font-size:13px;opacity:0.85;}\n" +
        "  .grid{display:grid;grid-template-columns:1fr 1fr;gap:14px;max-width:900px;}\n" +
        "  .field{display:flex;flex-direction:column;gap:4px;}\n" +
        "  .field.full{grid-column:1/-1;}\n" +
        "  label{font-size:12px;font-weight:600;color:var(--muted);}\n" +
        "  input[type=text],input[type=password]{padding:9px 11px;border:1px solid var(--border);border-radius:8px;font-size:13px;background:var(--card);}\n" +
        "  input:focus{outline:2px solid var(--primary-light);outline-offset:1px;}\n" +
        "  .hint{font-size:11px;color:var(--muted);}\n" +
        "  .checkbox-row{display:flex;align-items:center;gap:8px;font-size:13px;}\n" +
        "  button{background:var(--primary);color:#fff;border:none;padding:11px 22px;border-radius:8px;font-size:14px;font-weight:700;cursor:pointer;}\n" +
        "  button:hover{background:var(--primary-dark);}\n" +
        "  button:disabled{background:#a8a5b0;cursor:not-allowed;}\n" +
        "  .actions{max-width:900px;margin:18px 0;display:flex;gap:12px;align-items:center;}\n" +
        "  #logBox{max-width:900px;background:#0f172a;color:#d1fae5;font-family:ui-monospace,Consolas,monospace;font-size:12px;"+
        "    padding:14px 16px;border-radius:10px;height:340px;overflow-y:auto;white-space:pre-wrap;display:none;}\n" +
        "  #logBox .err{color:#fca5a5;}\n" +
        "  #status{max-width:900px;font-size:13px;font-weight:600;margin:8px 0;}\n" +
        "  #downloadLink{display:none;text-decoration:none;background:#059669;color:#fff;padding:11px 22px;border-radius:8px;font-weight:700;font-size:14px;}\n" +
        "  fieldset{border:1px solid var(--border);border-radius:10px;padding:14px 16px 16px;max-width:900px;margin:0 0 16px;}\n" +
        "  legend{font-size:12px;font-weight:700;color:var(--primary-dark);padding:0 6px;}\n" +
        "</style></head><body>\n" +
        "<header><h1>FireFlink Offline Report Generator</h1>" +
        "<p>Runs locally on this machine only (127.0.0.1) - credentials are never sent anywhere except FireFlink's own API.</p></header>\n" +
        "<form id=\"genForm\">\n" +
        "  <fieldset><legend>Credentials</legend><div class=\"grid\">\n" +
        "    <div class=\"field\"><label>Email</label><input type=\"text\" name=\"FF_EMAIL\" required></div>\n" +
        "    <div class=\"field\"><label>Password</label><input type=\"password\" name=\"FF_PASSWORD\" required></div>\n" +
        "  </div></fieldset>\n" +
        "  <fieldset><legend>Execution</legend><div class=\"grid\">\n" +
        "    <div class=\"field\"><label>License ID</label><input type=\"text\" name=\"FF_LICENSE_ID\" required></div>\n" +
        "    <div class=\"field\"><label>Execution ID</label><input type=\"text\" name=\"FF_EXECUTION_ID\" required></div>\n" +
        "    <div class=\"field full\"><label>Output Folder (leave blank to use default: ./reports)</label>" +
        "      <input type=\"text\" name=\"FF_OUTPUT_DIR\" placeholder=\"./reports\"><span class=\"hint\">Defaults to a 'reports' folder next to this server if left blank.</span></div>\n" +
        "  </div></fieldset>\n" +
        "  <fieldset><legend>Module/Script name enrichment (optional - leave blank to skip)</legend><div class=\"grid\">\n" +
        "    <div class=\"field\"><label>License Type</label><input type=\"text\" name=\"FF_LICENSE_TYPE\" placeholder=\"C-Professional\"></div>\n" +
        "    <div class=\"field\"><label>Project ID</label><input type=\"text\" name=\"FF_PROJECT_ID\" placeholder=\"PJT1005\"></div>\n" +
        "    <div class=\"field\"><label>Project Name</label><input type=\"text\" name=\"FF_PROJECT_NAME\" placeholder=\"Sapient Migration\"></div>\n" +
        "    <div class=\"field\"><label>Project Type</label><input type=\"text\" name=\"FF_PROJECT_TYPE\" placeholder=\"Web\"></div>\n" +
        "  </div></fieldset>\n" +
        "  <fieldset><legend>Advanced (optional)</legend><div class=\"grid\">\n" +
        "    <div class=\"field\"><label>Base URL</label><input type=\"text\" name=\"FF_BASE_URL\" placeholder=\"https://us-app.fireflink.com\"></div>\n" +
        "    <div class=\"field\"><div class=\"checkbox-row\"><input type=\"checkbox\" id=\"insecure\" name=\"FF_INSECURE_SSL\" value=\"true\">" +
        "      <label for=\"insecure\" style=\"font-weight:400;\">Bypass SSL cert validation (diagnostic only)</label></div></div>\n" +
        "  </div></fieldset>\n" +
        "  <div class=\"actions\">\n" +
        "    <button type=\"submit\" id=\"genBtn\">Generate Report</button>\n" +
        "    <a id=\"downloadLink\" href=\"#\">Download ZIP</a>\n" +
        "  </div>\n" +
        "</form>\n" +
        "<div id=\"status\"></div>\n" +
        "<div id=\"logBox\"></div>\n" +
        "<script>\n" +
        "  var form = document.getElementById('genForm');\n" +
        "  var genBtn = document.getElementById('genBtn');\n" +
        "  var logBox = document.getElementById('logBox');\n" +
        "  var statusEl = document.getElementById('status');\n" +
        "  var downloadLink = document.getElementById('downloadLink');\n" +
        "\n" +
        "  form.addEventListener('submit', function(e){\n" +
        "    e.preventDefault();\n" +
        "    genBtn.disabled = true;\n" +
        "    downloadLink.style.display = 'none';\n" +
        "    logBox.style.display = 'block';\n" +
        "    logBox.textContent = '';\n" +
        "    statusEl.textContent = 'Starting...';\n" +
        "\n" +
        "    var params = new URLSearchParams(new FormData(form));\n" +
        "    fetch('/generate', { method:'POST', headers:{'Content-Type':'application/x-www-form-urlencoded'}, body: params.toString() })\n" +
        "      .then(function(res){\n" +
        "        if(res.status === 409){ statusEl.textContent = 'A generation is already running.'; genBtn.disabled = false; return; }\n" +
        "        if(res.status !== 202){ statusEl.textContent = 'Failed to start.'; genBtn.disabled = false; return; }\n" +
        "        statusEl.textContent = 'Running...';\n" +
        "        var es = new EventSource('/logs');\n" +
        "        es.addEventListener('log', function(ev){\n" +
        "          var isErr = /ERROR|Exception/.test(ev.data);\n" +
        "          var line = document.createElement('div');\n" +
        "          if(isErr) line.className = 'err';\n" +
        "          line.textContent = ev.data;\n" +
        "          logBox.appendChild(line);\n" +
        "          logBox.scrollTop = logBox.scrollHeight;\n" +
        "        });\n" +
        "        es.addEventListener('done', function(ev){\n" +
        "          es.close();\n" +
        "          genBtn.disabled = false;\n" +
        "          var code = ev.data;\n" +
        "          if(code === '0'){\n" +
        "            statusEl.textContent = 'Done - report generated successfully.';\n" +
        "            downloadLink.href = '/download';\n" +
        "            downloadLink.style.display = 'inline-block';\n" +
        "          } else {\n" +
        "            statusEl.textContent = 'Finished with an error (exit code ' + code + '). Check the log above.';\n" +
        "          }\n" +
        "        });\n" +
        "        es.onerror = function(){ statusEl.textContent = 'Log stream disconnected.'; genBtn.disabled = false; };\n" +
        "      })\n" +
        "      .catch(function(err){ statusEl.textContent = 'Error: ' + err; genBtn.disabled = false; });\n" +
        "  });\n" +
        "</script>\n" +
        "</body></html>\n";
}
