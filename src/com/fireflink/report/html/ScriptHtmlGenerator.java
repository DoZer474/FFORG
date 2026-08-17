package com.fireflink.report.html;

import com.fasterxml.jackson.core.JsonEncoding;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fireflink.report.model.ScriptInfo;
import com.fireflink.report.model.StepResult;

import java.io.BufferedOutputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;

import static com.fireflink.report.html.HtmlUtil.esc;

/**
 * Renders a script's ENTIRE step tree as ONE self-contained HTML file. The
 * tree is embedded as Base64-encoded JSON, written by STREAMING Jackson's
 * JsonGenerator directly into a Base64 encoding OutputStream that writes
 * straight to disk - at no point is the full JSON (or full Base64 string)
 * ever held in memory as one String.
 *
 * On load, JS decodes the Base64 back to a UTF-8 string and JSON.parse()s it
 * once. The main list only ever renders ONE drill-down level of the tree at
 * a time (click a group -> see its children; breadcrumb to go back up), so
 * DOM size stays bounded no matter how many total steps the script has.
 * Rows render in requestAnimationFrame batches. Status filter chips (All /
 * Passed / Failed / Terminated / Skipped) combine with the text filter to
 * narrow the current level. Clicking a leaf step opens a right-side
 * slide-over panel (50% width): API/workbench steps (identified by the
 * presence of workbenchExecutionResult) render as a Postman-style
 * request/response view (method, URL, headers, body, status code, response
 * body, assertion results); all other steps render as a plain NLP-style
 * message/inputs/return view, with any JSON-looking values pretty-printed
 * instead of dumped as one unreadable line. IndexedDB caches the parsed
 * tree per scriptId as a best-effort speed-up for reopening.
 */
public class ScriptHtmlGenerator {

    private final ObjectMapper mapper = new ObjectMapper();

    public void generate(ScriptInfo script, List<StepResult> rootSteps,
                          String prevScriptHtml, String nextScriptHtml,
                          Path outputFile) throws IOException {

        String title = esc(script.scriptName != null ? script.scriptName : script.scriptId);
        String scriptId = esc(script.scriptId);

        try (OutputStream fileOut = new BufferedOutputStream(Files.newOutputStream(outputFile))) {
            Writer w = new OutputStreamWriter(fileOut, StandardCharsets.UTF_8);

            w.write("<!DOCTYPE html>\n<html lang=\"en\"><head><meta charset=\"UTF-8\">");
            w.write("<title>" + title + "</title>");
            w.write("<link rel=\"stylesheet\" href=\"../../assets/style.css\"></head><body>");
            w.write(Assets.LOADER_OVERLAY_HTML);

            w.write("<header class=\"page-header\">");
            w.write(Assets.LOGO_HTML);
            w.write("<a class=\"back-link\" href=\"../../index.html\">&larr; Dashboard</a>");
            w.write("<h1>" + title + "</h1>");
            w.write("<div class=\"meta\">"
                    + (script.moduleName != null ? "Module: <strong>" + esc(script.moduleName) + "</strong> &nbsp;|&nbsp; " : "")
                    + "Script ID: <code>" + scriptId + "</code> "
                    + "<span id=\"totalCount\" class=\"meta-count\"></span></div>");
            w.write("</header>");

            w.write("<nav class=\"script-nav\">");
            if (prevScriptHtml != null) {
                w.write("<a href=\"" + esc(prevScriptHtml) + "\">&laquo; Previous Script</a>");
            } else {
                w.write("<span class=\"disabled\">&laquo; Previous Script</span>");
            }
            if (nextScriptHtml != null) {
                w.write("<a href=\"" + esc(nextScriptHtml) + "\">Next Script &raquo;</a>");
            } else {
                w.write("<span class=\"disabled\">Next Script &raquo;</span>");
            }
            w.write("</nav>");

            w.write("<div class=\"toolbar\">");
            w.write("<nav id=\"breadcrumb\" class=\"breadcrumb\"></nav>");
            w.write("<input type=\"text\" id=\"stepFilter\" class=\"filter-input\" placeholder=\"Filter steps at this level by name or status...\">");
            w.write("</div>");
            w.write("<div id=\"statusChips\" class=\"status-chips\"></div>");

            w.write("<main class=\"step-list\" id=\"stepList\"></main>");
            w.write("<div id=\"listStatus\" class=\"list-status\"></div>");

            w.write("<div id=\"scrim\" class=\"scrim\"></div>");
            w.write("<aside id=\"detailPanel\" class=\"detail-panel\">");
            w.write("  <div class=\"detail-panel-header\">");
            w.write("    <div id=\"detailTitle\" class=\"detail-panel-title\"></div>");
            w.write("    <button type=\"button\" id=\"detailClose\" class=\"detail-close\">&times;</button>");
            w.write("  </div>");
            w.write("  <div id=\"detailBody\" class=\"detail-panel-body\"></div>");
            w.write("</aside>");

            // --- stream the tree as Base64 JSON, never materializing the whole thing as one String ---
            w.write("<script id=\"stepData\" type=\"application/base64\">");
            w.flush(); // make sure all text above is actually written before we switch to the raw byte stream

            OutputStream b64Out = Base64.getEncoder().wrap(new NonClosingOutputStream(fileOut));
            try (JsonGenerator gen = mapper.getFactory().createGenerator(b64Out, JsonEncoding.UTF8)) {
                mapper.writeValue(gen, rootSteps);
            }
            b64Out.flush();
            b64Out.close(); // flushes trailing base64 padding bytes; underlying stream stays open (see NonClosingOutputStream)

            w.write("</script>");
            w.write("<script>" + buildAppJs(scriptId) + "</script>");

            w.write("</body></html>");
            w.flush();
        }
    }

    /** Prevents Base64's wrap() stream from closing our shared file stream when IT closes - we still need to write more HTML afterward. */
    private static class NonClosingOutputStream extends FilterOutputStream {
        NonClosingOutputStream(OutputStream out) { super(out); }
        @Override public void close() throws IOException { flush(); }
        @Override public void write(byte[] b, int off, int len) throws IOException { out.write(b, off, len); }
    }

    private String buildAppJs(String scriptId) {
        return ""
            + "(function(){\n"
            + "  var SCRIPT_ID = '" + scriptId + "';\n"
            + "  var root = [];\n"
            + "  var stack = [];\n" // breadcrumb stack: [{name, nodes}]
            + "  var listEl = document.getElementById('stepList');\n"
            + "  var breadcrumbEl = document.getElementById('breadcrumb');\n"
            + "  var filterEl = document.getElementById('stepFilter');\n"
            + "  var chipsEl = document.getElementById('statusChips');\n"
            + "  var statusEl = document.getElementById('listStatus');\n"
            + "  var totalCountEl = document.getElementById('totalCount');\n"
            + "  var panel = document.getElementById('detailPanel');\n"
            + "  var scrim = document.getElementById('scrim');\n"
            + "  var detailTitle = document.getElementById('detailTitle');\n"
            + "  var detailBody = document.getElementById('detailBody');\n"
            + "  var filterTimer = null;\n"
            + "  var renderToken = 0;\n"
            + "  var activeStatusFilter = null;\n"
            + "\n"
            + "  function countAll(nodes){\n"
            + "    var c = 0;\n"
            + "    for(var i=0;i<nodes.length;i++){ c += 1 + countAll(nodes[i].children||[]); }\n"
            + "    return c;\n"
            + "  }\n"
            + "\n"
            + "  function esc(s){\n"
            + "    if(s==null) return '';\n"
            + "    var d=document.createElement('div'); d.textContent=String(s); return d.innerHTML;\n"
            + "  }\n"
            + "\n"
            + "  function b64ToUtf8(b64){\n"
            + "    var binary = atob(b64);\n"
            + "    var len = binary.length;\n"
            + "    var bytes = new Uint8Array(len);\n"
            + "    for(var i=0;i<len;i++){ bytes[i] = binary.charCodeAt(i); }\n"
            + "    return new TextDecoder('utf-8').decode(bytes);\n"
            + "  }\n"
            + "\n"
            + "  function statusClass(status){\n"
            + "    var s=(status||'').toUpperCase();\n"
            + "    if(s.indexOf('TERMINAT')!==-1) return 'terminate';\n"
            + "    if(s.indexOf('ABORT')!==-1) return 'terminate';\n"
            + "    if(s.indexOf('PASS')===0) return 'pass';\n"
            + "    if(s.indexOf('FAIL')===0) return 'fail';\n"
            + "    if(s.indexOf('WARN')!==-1) return 'warn';\n"
            + "    if(s.indexOf('SKIP')!==-1) return 'skip';\n"
            + "    return 'unknown';\n"
            + "  }\n"
            + "  function statusText(status){\n"
            + "    var c = statusClass(status);\n"
            + "    return {pass:'Passed',fail:'Failed',warn:'Warning',skip:'Skipped',terminate:(status||'Terminated')}[c] || (status||'N/A');\n"
            + "  }\n"
            + "  function badge(status){\n"
            + "    return '<span class=\"status-badge status-'+statusClass(status)+'\">'+esc(statusText(status))+'</span>';\n"
            + "  }\n"
            + "\n"
            + "  function renderBreadcrumb(){\n"
            + "    var parts = ['<a href=\"#\" data-level=\"-1\">'+esc(SCRIPT_ID)+'</a>'];\n"
            + "    for(var i=0;i<stack.length;i++){\n"
            + "      parts.push('<span class=\"crumb-sep\">/</span><a href=\"#\" data-level=\"'+i+'\">'+esc(stack[i].name)+'</a>');\n"
            + "    }\n"
            + "    breadcrumbEl.innerHTML = parts.join('');\n"
            + "    Array.prototype.forEach.call(breadcrumbEl.querySelectorAll('a'), function(a){\n"
            + "      a.addEventListener('click', function(e){\n"
            + "        e.preventDefault();\n"
            + "        var lvl = parseInt(a.getAttribute('data-level'), 10);\n"
            + "        if(lvl === -1){ stack = []; renderLevel(root); }\n"
            + "        else { stack = stack.slice(0, lvl+1); renderLevel(stack[lvl].nodes); }\n"
            + "      });\n"
            + "    });\n"
            + "  }\n"
            + "\n"
            + "  function currentNodes(){\n"
            + "    return stack.length ? stack[stack.length-1].nodes : root;\n"
            + "  }\n"
            + "\n"
            + "  function renderStatusChips(nodes){\n"
            + "    var counts = {};\n"
            + "    for(var i=0;i<nodes.length;i++){\n"
            + "      var c = statusClass(nodes[i].status);\n"
            + "      counts[c] = (counts[c]||0) + 1;\n"
            + "    }\n"
            + "    var order = ['pass','fail','terminate','warn','skip','unknown'];\n"
            + "    var html = '<button type=\"button\" class=\"chip'+(activeStatusFilter===null?' chip-active':'')+'\" data-status=\"\">All ('+nodes.length+')</button>';\n"
            + "    for(var j=0;j<order.length;j++){\n"
            + "      var key = order[j];\n"
            + "      if(!counts[key]) continue;\n"
            + "      html += '<button type=\"button\" class=\"chip chip-'+key+(activeStatusFilter===key?' chip-active':'')+'\" data-status=\"'+key+'\">'+statusText(key==='unknown'?null:key.toUpperCase())+' ('+counts[key]+')</button>';\n"
            + "    }\n"
            + "    chipsEl.innerHTML = html;\n"
            + "    Array.prototype.forEach.call(chipsEl.querySelectorAll('.chip'), function(btn){\n"
            + "      btn.addEventListener('click', function(){\n"
            + "        var s = btn.getAttribute('data-status');\n"
            + "        activeStatusFilter = s ? s : null;\n"
            + "        renderStatusChips(currentNodes());\n"
            + "        applyFilters();\n"
            + "      });\n"
            + "    });\n"
            + "  }\n"
            + "\n"
            + "  function renderLevel(nodes){\n"
            + "    renderToken++;\n"
            + "    var myToken = renderToken;\n"
            + "    activeStatusFilter = null;\n"
            + "    renderBreadcrumb();\n"
            + "    renderStatusChips(nodes);\n"
            + "    listEl.innerHTML = '';\n"
            + "    filterEl.value = '';\n"
            + "    statusEl.textContent = '';\n"
            + "    renderRowsBatched(nodes, myToken);\n"
            + "  }\n"
            + "\n"
            + "  function renderRowsBatched(nodes, myToken){\n"
            + "    var BATCH = 150;\n"
            + "    var i = 0;\n"
            + "    var frag;\n"
            + "    function step(){\n"
            + "      if(myToken !== renderToken) return;\n"
            + "      frag = document.createDocumentFragment();\n"
            + "      var end = Math.min(i+BATCH, nodes.length);\n"
            + "      for(; i<end; i++){ frag.appendChild(buildRow(nodes[i], i+1)); }\n"
            + "      listEl.appendChild(frag);\n"
            + "      if(i < nodes.length){\n"
            + "        statusEl.textContent = 'Rendering ' + i + ' / ' + nodes.length + ' at this level...';\n"
            + "        requestAnimationFrame(step);\n"
            + "      } else {\n"
            + "        statusEl.textContent = nodes.length + ' item' + (nodes.length===1?'':'s') + ' at this level.';\n"
            + "      }\n"
            + "    }\n"
            + "    requestAnimationFrame(step);\n"
            + "  }\n"
            + "\n"
            + "  function isApiStep(node){\n"
            + "    return !!(node.workbenchExecutionResult);\n"
            + "  }\n"
            + "\n"
            + "  function buildRow(node, num){\n"
            + "    var hasChildren = node.children && node.children.length > 0;\n"
            + "    var row = document.createElement('div');\n"
            + "    row.className = 'step-row list-row';\n"
            + "    row.dataset.searchText = (node.name+' '+(node.status||'')).toLowerCase();\n"
            + "    row.dataset.statusClass = statusClass(node.status);\n"
            + "    var icon = hasChildren ? '&#9654;' : (isApiStep(node) ? '&#128225;' : '');\n"
            + "    var mid = '';\n"
            + "    if(hasChildren && node.stepResultStats){\n"
            + "      var st = node.stepResultStats;\n"
            + "      mid = (st.totalPassed||0)+' pass &middot; '+(st.totalFailed||0)+' fail &middot; '+(st.totalSkipped||0)+' skip';\n"
            + "    } else if(!hasChildren && node.message){\n"
            + "      var preview = node.message.length>80 ? node.message.substring(0,80)+'...' : node.message;\n"
            + "      mid = esc(preview);\n"
            + "    }\n"
            + "    row.innerHTML = '<span class=\"step-expand-icon\">'+icon+'</span>'\n"
            + "      + '<span class=\"step-num\">'+num+'.</span>'\n"
            + "      + '<span class=\"step-desc\">'+esc(node.name)+'</span>'\n"
            + "      + '<span class=\"step-msg-preview\">'+mid+'</span>'\n"
            + "      + badge(node.status);\n"
            + "    row.addEventListener('click', function(){\n"
            + "      if(hasChildren){\n"
            + "        stack.push({name: node.name, nodes: node.children});\n"
            + "        renderLevel(node.children);\n"
            + "      } else {\n"
            + "        openDetail(node);\n"
            + "      }\n"
            + "    });\n"
            + "    return row;\n"
            + "  }\n"
            + "\n"
            + "  function tryParseJson(str){\n"
            + "    if(typeof str !== 'string') return null;\n"
            + "    var t = str.trim();\n"
            + "    if(!t || (t[0]!=='{' && t[0]!=='[')) return null;\n"
            + "    try{ return JSON.parse(t); }catch(e){ return null; }\n"
            + "  }\n"
            + "  function prettyBlock(value){\n"
            + "    if(value == null) return '';\n"
            + "    if(typeof value === 'object'){\n"
            + "      return '<pre class=\"detail-pre\">'+esc(JSON.stringify(value, null, 2))+'</pre>';\n"
            + "    }\n"
            + "    var parsed = tryParseJson(value);\n"
            + "    if(parsed !== null){\n"
            + "      return '<pre class=\"detail-pre\">'+esc(JSON.stringify(parsed, null, 2))+'</pre>';\n"
            + "    }\n"
            + "    return '<pre class=\"detail-pre\">'+esc(value)+'</pre>';\n"
            + "  }\n"
            + "  function safeParse(str, fallback){\n"
            + "    try{ return typeof str === 'string' ? JSON.parse(str) : str; }catch(e){ return fallback; }\n"
            + "  }\n"
            + "  function buildVarMap(varsRaw){\n"
            + "    var map = safeParse(varsRaw, {});\n"
            + "    return map || {};\n"
            + "  }\n"
            + "  function resolveVars(str, varMap){\n"
            + "    if(typeof str !== 'string') return str;\n"
            + "    return str.replace(/\\$\\{(VAR[a-zA-Z0-9-]+)\\}/g, function(whole, id){\n"
            + "      var key = '${'+id+'}';\n"
            + "      var v = varMap[key];\n"
            + "      if(!v) return whole;\n"
            + "      if(v.masked) return '\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022 (masked)';\n"
            + "      return (v.value==null || v.value==='null') ? whole : String(v.value);\n"
            + "    });\n"
            + "  }\n"
            + "  function methodBadgeClass(method){\n"
            + "    var m = (method||'').toUpperCase();\n"
            + "    if(m==='GET') return 'method-get';\n"
            + "    if(m==='POST') return 'method-post';\n"
            + "    if(m==='PUT') return 'method-put';\n"
            + "    if(m==='DELETE') return 'method-delete';\n"
            + "    if(m==='PATCH') return 'method-patch';\n"
            + "    return 'method-other';\n"
            + "  }\n"
            + "  function headerTable(headers){\n"
            + "    if(!headers || !headers.length) return '<div class=\"detail-empty\">None</div>';\n"
            + "    var enabled = headers.filter(function(h){ return h.isEnabled !== false; });\n"
            + "    if(!enabled.length) return '<div class=\"detail-empty\">None enabled</div>';\n"
            + "    var html = '<table class=\"detail-table\"><tr><th>Header</th><th>Value</th></tr>';\n"
            + "    enabled.forEach(function(h){ html += '<tr><td>'+esc(h.name)+'</td><td>'+esc(h.value)+'</td></tr>'; });\n"
            + "    return html + '</table>';\n"
            + "  }\n"
            + "\n"
            + "  function buildApiDetailHtml(node){\n"
            + "    var wer = node.workbenchExecutionResult;\n"
            + "    var html = '';\n"
            + "    html += '<div class=\"detail-status\">'+badge(node.status)+'</div>';\n"
            + "    if(node.executionDurationInHourMinSecFormat){\n"
            + "      html += '<div class=\"detail-row-line\"><strong>Duration:</strong> '+esc(node.executionDurationInHourMinSecFormat)+'</div>';\n"
            + "    }\n"
            + "    if(node.message){\n"
            + "      html += '<div class=\"detail-row-line\">'+esc(node.message)+'</div>';\n"
            + "    }\n"
            + "    try{\n"
            + "      var reqOuter = safeParse(wer.request, null);\n"
            + "      var reqInner = reqOuter ? safeParse(reqOuter.request, null) : null;\n"
            + "      var varMap = reqOuter ? buildVarMap(reqOuter.variables) : {};\n"
            + "      var resp = typeof wer.response === 'string' ? safeParse(wer.response, null) : wer.response;\n"
            + "      var url = reqInner ? resolveVars(reqInner.url, varMap) : '';\n"
            + "      var enabledHeaders = reqInner && reqInner.headers ? reqInner.headers.filter(function(h){ return h.isEnabled !== false; }).map(function(h){ return {name:h.name, value: resolveVars(h.value, varMap)}; }) : [];\n"
            + "      var bodyRaw = reqInner ? (reqInner.body || reqInner.rawBody || reqInner.requestBody || reqInner.raw"
            + " || reqInner.bodyContent || reqInner.jsonBody || reqInner.payload"
            + " || (reqInner.body && reqInner.body.raw) || (reqInner.rawBodyData && reqInner.rawBodyData.raw)) : null;\n"
            + "      var bodyResolved = bodyRaw ? resolveVars(typeof bodyRaw === 'string' ? bodyRaw : JSON.stringify(bodyRaw), varMap) : null;\n"
            + "\n"
            + "      if(reqInner){\n"
            + "        html += '<div class=\"request-line\"><span class=\"method-badge '+methodBadgeClass(reqInner.method)+'\">'+esc(reqInner.method)+'</span>'\n"
            + "          + '<span class=\"request-url\">'+esc(url)+'</span></div>';\n"
            + "      }\n"
            + "      if(resp && resp.statusCode){\n"
            + "        var codeClass = (resp.statusCode>=200 && resp.statusCode<300) ? 'pass' : 'fail';\n"
            + "        html += '<div class=\"response-status-line\">Result: <span class=\"status-badge status-'+codeClass+'\">'+esc(resp.statusCode)+' '+esc(resp.statusCodeValue||'')+'</span></div>';\n"
            + "      }\n"
            + "\n"
            // ---- outer tabs: Request/Response | Variables ----
            + "      html += '<div class=\"api-tabs\"><div class=\"tabs-row\">'\n"
            + "        + '<button type=\"button\" class=\"tab-btn active\" data-target=\"pane-reqres\">Request / Response</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-vars\">Variables</button>'\n"
            + "        + '</div>';\n"
            + "\n"
            + "      html += '<div class=\"tab-pane active\" id=\"pane-reqres\">';\n"
            // ---- inner tabs: Request Body | Request Headers | cURL | Response Body | Response Headers | Assert Results ----
            + "      html += '<div class=\"api-tabs\"><div class=\"tabs-row\">'\n"
            + "        + '<button type=\"button\" class=\"tab-btn active\" data-target=\"pane-reqbody\">Request Body</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-reqheaders\">Request Headers</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-curl\">cURL</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-resbody\">Response Body</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-resheaders\">Response Headers</button>'\n"
            + "        + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-assertions\">Assert Results</button>'\n"
            + "        + '</div>';\n"
            + "\n"
            + "      html += '<div class=\"tab-pane active\" id=\"pane-reqbody\">' + (bodyResolved ? prettyBlock(bodyResolved) : ('<div class=\"detail-empty\">No body field found under any known name. Raw request config below - if you can see a body value in it, tell me the field name:</div>' + prettyBlock(reqInner))) + '</div>';\n"
            + "      html += '<div class=\"tab-pane\" id=\"pane-reqheaders\">' + headerTable(enabledHeaders) + '</div>';\n"
            + "      html += '<div class=\"tab-pane\" id=\"pane-curl\">' + prettyBlock(buildCurl(reqInner, url, enabledHeaders, bodyResolved)) + '</div>';\n"
            + "      html += '<div class=\"tab-pane\" id=\"pane-resbody\">' + (resp && resp.responseBody ? prettyBlock(resp.responseBody) : '<div class=\"detail-empty\">No response body</div>') + '</div>';\n"
            + "      html += '<div class=\"tab-pane\" id=\"pane-resheaders\">' + (resp ? headerTable(resp.headers) : '<div class=\"detail-empty\">No response</div>') + '</div>';\n"
            + "\n"
            + "      var assertHtml = '';\n"
            + "      if(resp && resp.responseAssertionResults && resp.responseAssertionResults.length){\n"
            + "        resp.responseAssertionResults.forEach(function(a){\n"
            + "          var ok = (a.state||'').toUpperCase()==='SUCCESS';\n"
            + "          assertHtml += '<div class=\"assertion-row assertion-'+(ok?'pass':'fail')+'\">'\n"
            + "            + '<span class=\"assertion-icon\">'+(ok?'\\u2713':'\\u2717')+'</span>'\n"
            + "            + '<span>'+esc(a.resultMessage||(a.assertionRequestDto&&a.assertionRequestDto.name))+'</span></div>';\n"
            + "        });\n"
            + "      } else { assertHtml = '<div class=\"detail-empty\">No assertion results</div>'; }\n"
            + "      html += '<div class=\"tab-pane\" id=\"pane-assertions\">';\n"
            + "      if(reqInner && reqInner.basicAssertions && reqInner.basicAssertions.length){\n"
            + "        html += '<div class=\"section-sublabel\">Configured</div><table class=\"detail-table\"><tr><th>Name</th><th>Condition</th></tr>';\n"
            + "        reqInner.basicAssertions.forEach(function(a){ html += '<tr><td>'+esc(a.name)+'</td><td>'+esc(a.lhs)+' '+esc(a.operator)+' '+esc(a.rhs)+'</td></tr>'; });\n"
            + "        html += '</table><div class=\"section-sublabel\">Results</div>';\n"
            + "      }\n"
            + "      html += assertHtml + '</div>';\n"
            + "      html += '</div>'; // end inner api-tabs\n"
            + "      html += '</div>'; // end pane-reqres\n"
            + "\n"
            // ---- Variables tab content ----
            + "      html += '<div class=\"tab-pane\" id=\"pane-vars\">';\n"
            + "      if(resp && resp.updatedVariables && resp.updatedVariables.length){\n"
            + "        html += '<table class=\"detail-table\"><tr><th>Name</th><th>Value</th><th>Group</th></tr>';\n"
            + "        resp.updatedVariables.forEach(function(uv){\n"
            + "          var v = uv.variable || {};\n"
            + "          var val = v.masked ? '\\u2022\\u2022\\u2022\\u2022\\u2022\\u2022 (masked)' : (v.value==null?'':String(v.value));\n"
            + "          var preview = val.length>200 ? val.substring(0,200)+'...' : val;\n"
            + "          html += '<tr><td>'+esc(v.name)+'</td><td>'+esc(preview)+'</td><td>'+esc(uv.group||'')+'</td></tr>';\n"
            + "        });\n"
            + "        html += '</table>';\n"
            + "      } else {\n"
            + "        html += '<div class=\"detail-empty\">No variables updated by this step</div>';\n"
            + "      }\n"
            + "      html += '</div>'; // end pane-vars\n"
            + "      html += '</div>'; // end outer api-tabs\n"
            + "    }catch(err){\n"
            + "      html += '<div class=\"section-label\">Raw data</div>' + prettyBlock(wer);\n"
            + "    }\n"
            + "    if(!node.isScreenshotNotApplicable){\n"
            + "      html += '<div class=\"screenshot-pending\">Screenshot available in FireFlink UI (not yet embedded in offline report)</div>';\n"
            + "    }\n"
            + "    return html;\n"
            + "  }\n"
            + "\n"
            + "  function buildCurl(reqInner, url, headers, body){\n"
            + "    if(!reqInner) return '';\n"
            + "    var lines = [\"curl -X \" + (reqInner.method||'GET') + \" '\" + url + \"'\"];\n"
            + "    headers.forEach(function(h){ lines.push(\"  -H '\" + h.name + \": \" + h.value + \"'\"); });\n"
            + "    if(body){ lines.push(\"  -d '\" + String(body).replace(/'/g, \"'\\\\''\") + \"'\"); }\n"
            + "    return lines.join(\" \\\\\\n\");\n"
            + "  }\n"
            + "\n"
            + "  function buildNlpDetailHtml(node){\n"
            + "    var html = '<div class=\"detail-status\">'+badge(node.status)+'</div>';\n"
            + "    if(node.executionDurationInHourMinSecFormat){\n"
            + "      html += '<div class=\"detail-row-line\"><strong>Duration:</strong> '+esc(node.executionDurationInHourMinSecFormat)+'</div>';\n"
            + "    }\n"
            + "    html += '<div class=\"api-tabs\"><div class=\"tabs-row\">'\n"
            + "      + '<button type=\"button\" class=\"tab-btn active\" data-target=\"pane-nlp-details\">Details</button>'\n"
            + "      + '<button type=\"button\" class=\"tab-btn\" data-target=\"pane-nlp-vars\">Variables</button>'\n"
            + "      + '</div>';\n"
            + "\n"
            + "    html += '<div class=\"tab-pane active\" id=\"pane-nlp-details\">';\n"
            + "    if(node.message){\n"
            + "      var parsedMsg = tryParseJson(node.message);\n"
            + "      html += '<div class=\"section-label\">Message</div>';\n"
            + "      html += parsedMsg ? prettyBlock(parsedMsg) : ('<div class=\"step-message\">'+esc(node.message)+'</div>');\n"
            + "    }\n"
            + "    if(node.stepInputs && node.stepInputs.length){\n"
            + "      html += '<div class=\"section-label\">Inputs</div>';\n"
            + "      node.stepInputs.forEach(function(inp){\n"
            + "        if(/^VAR[0-9a-fA-F-]+$/.test(inp.name||'')) return;\n" // variable-id refs shown in the Variables tab instead
            + "        var pVal = tryParseJson(inp.value), pAct = tryParseJson(inp.actualValue);\n"
            + "        if(pVal || pAct){\n"
            + "          html += '<div class=\"section-sublabel\">'+esc(inp.name)+'</div>';\n"
            + "          if(pVal) html += '<div class=\"detail-empty\">Value:</div>'+prettyBlock(pVal);\n"
            + "          if(pAct) html += '<div class=\"detail-empty\">Actual Value:</div>'+prettyBlock(pAct);\n"
            + "        } else {\n"
            + "          html += '<table class=\"detail-table\"><tr><th>Name</th><th>Value</th><th>Actual Value</th></tr>'\n"
            + "            + '<tr><td>'+esc(inp.name)+'</td><td>'+esc(inp.value)+'</td><td>'+esc(inp.actualValue)+'</td></tr></table>';\n"
            + "        }\n"
            + "      });\n"
            + "    }\n"
            + "    if(node.stepReturn && node.stepReturn.value){\n"
            + "      var pRet = tryParseJson(node.stepReturn.value);\n"
            + "      html += '<div class=\"section-label\">Return: '+esc(node.stepReturn.name)+'</div>';\n"
            + "      html += pRet ? prettyBlock(pRet) : ('<pre class=\"detail-pre\">'+esc(node.stepReturn.value)+'</pre>');\n"
            + "    }\n"
            + "    html += '</div>'; // end pane-nlp-details\n"
            + "\n"
            + "    html += '<div class=\"tab-pane\" id=\"pane-nlp-vars\">';\n"
            + "    var varRows = [];\n"
            + "    if(node.stepInputs){\n"
            + "      node.stepInputs.forEach(function(inp){\n"
            + "        if(/^VAR[0-9a-fA-F-]+$/.test(inp.name||'')){ varRows.push({name: inp.value, value: '(referenced)', group:'Input reference'}); }\n"
            + "      });\n"
            + "    }\n"
            + "    if(node.stepReturn && node.stepReturn.value){\n"
            + "      varRows.push({name: node.stepReturn.name, value: node.stepReturn.value, group:'Return value'});\n"
            + "    }\n"
            + "    if(varRows.length){\n"
            + "      html += '<table class=\"detail-table\"><tr><th>Name</th><th>Value</th><th>Group</th></tr>';\n"
            + "      varRows.forEach(function(v){\n"
            + "        var val = String(v.value||'');\n"
            + "        var preview = val.length>200 ? val.substring(0,200)+'...' : val;\n"
            + "        html += '<tr><td>'+esc(v.name)+'</td><td>'+esc(preview)+'</td><td>'+esc(v.group)+'</td></tr>';\n"
            + "      });\n"
            + "      html += '</table>';\n"
            + "    } else {\n"
            + "      html += '<div class=\"detail-empty\">No variables referenced or updated by this step</div>';\n"
            + "    }\n"
            + "    html += '</div>'; // end pane-nlp-vars\n"
            + "    html += '</div>'; // end api-tabs\n"
            + "\n"
            + "    if(!node.isScreenshotNotApplicable){\n"
            + "      html += '<div class=\"screenshot-pending\">Screenshot available in FireFlink UI (not yet embedded in offline report)</div>';\n"
            + "    }\n"
            + "    return html;\n"
            + "  }\n"
            + "\n"
            + "  function openDetail(node){\n"
            + "    detailTitle.textContent = node.name || '';\n"
            + "    detailBody.innerHTML = isApiStep(node) ? buildApiDetailHtml(node) : buildNlpDetailHtml(node);\n"
            + "    panel.classList.add('open');\n"
            + "    scrim.classList.add('open');\n"
            + "  }\n"
            + "  function closeDetail(){\n"
            + "    panel.classList.remove('open');\n"
            + "    scrim.classList.remove('open');\n"
            + "  }\n"
            + "  document.getElementById('detailClose').addEventListener('click', closeDetail);\n"
            + "  scrim.addEventListener('click', closeDetail);\n"
            + "  document.addEventListener('keydown', function(e){ if(e.key==='Escape') closeDetail(); });\n"
            + "\n"
            + "  detailBody.addEventListener('click', function(e){\n"
            + "    var btn = e.target.closest('.tab-btn');\n"
            + "    if(!btn) return;\n"
            + "    var container = btn.closest('.api-tabs');\n"
            + "    if(!container) return;\n"
            + "    var tabsRow = btn.parentElement;\n"
            + "    Array.prototype.forEach.call(tabsRow.querySelectorAll('.tab-btn'), function(b){ b.classList.remove('active'); });\n"
            + "    btn.classList.add('active');\n"
            + "    var target = btn.getAttribute('data-target');\n"
            + "    Array.prototype.forEach.call(container.children, function(child){\n"
            + "      if(child.classList && child.classList.contains('tab-pane')){\n"
            + "        child.classList.toggle('active', child.id === target);\n"
            + "      }\n"
            + "    });\n"
            + "  });\n"
            + "\n"
            + "  function applyFilters(){\n"
            + "    var q = filterEl.value.trim().toLowerCase();\n"
            + "    var rows = listEl.querySelectorAll('.list-row');\n"
            + "    var visible = 0;\n"
            + "    rows.forEach(function(r){\n"
            + "      var textOk = !q || r.dataset.searchText.indexOf(q) !== -1;\n"
            + "      var statusOk = !activeStatusFilter || r.dataset.statusClass === activeStatusFilter;\n"
            + "      var match = textOk && statusOk;\n"
            + "      r.style.display = match ? '' : 'none';\n"
            + "      if(match) visible++;\n"
            + "    });\n"
            + "    statusEl.textContent = visible + ' / ' + rows.length + ' items shown at this level.';\n"
            + "  }\n"
            + "\n"
            + "  filterEl.addEventListener('input', function(){\n"
            + "    clearTimeout(filterTimer);\n"
            + "    filterTimer = setTimeout(applyFilters, 150);\n"
            + "  });\n"
            + "\n"
            + "  function init(data){\n"
            + "    root = data;\n"
            + "    totalCountEl.textContent = '(' + countAll(root) + ' total steps)';\n"
            + "    renderLevel(root);\n"
            + "  }\n"
            + "\n"
            + "  var raw = document.getElementById('stepData').textContent;\n"
            + "  var data = JSON.parse(b64ToUtf8(raw));\n"
            + "\n"
            + "  try{\n"
            + "    var idbReq = indexedDB.open('fireflinkReports', 1);\n"
            + "    idbReq.onupgradeneeded = function(e){\n"
            + "      var db = e.target.result;\n"
            + "      if(!db.objectStoreNames.contains('scripts')){ db.createObjectStore('scripts', {keyPath:'scriptId'}); }\n"
            + "    };\n"
            + "    idbReq.onsuccess = function(e){\n"
            + "      var db = e.target.result;\n"
            + "      try{\n"
            + "        var tx = db.transaction('scripts','readwrite');\n"
            + "        tx.objectStore('scripts').put({scriptId: SCRIPT_ID, data: data, cachedAt: Date.now()});\n"
            + "      }catch(err){ /* non-fatal */ }\n"
            + "    };\n"
            + "    idbReq.onerror = function(){ /* IndexedDB unavailable - fine */ };\n"
            + "  }catch(err){ /* IndexedDB not supported at all - fine */ }\n"
            + "\n"
            + "  init(data);\n"
            + "})();\n";
    }
}
