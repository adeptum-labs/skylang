/*
 * SkyLang is a specification language whose compiler writes the code.
 * Copyright © 2026 Adeptum AB, Org.nr 559494-1824.
 *
 * This program is free software: you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY
 * or FITNESS FOR A PARTICULAR PURPOSE. See the GNU General Public License for
 * more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program. If not, see <https://www.gnu.org/licenses/>.
 *
 * Website: https://www.adeptum.se
 * Contact: info@adeptum.se
 */

package com.adeptum.skylang.preview;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The studio shell: a lightweight JDK HTTP server the native CLI can host. It frames the
 * live-rendered views served by the container subprocess and exposes the edit actions (reshape a
 * view in natural language, then accept or reject), delegating them to an {@link EditHandler}.
 */
public final class StudioServer implements AutoCloseable {

    private final HttpServer server;
    private volatile int appPort;

    public StudioServer(int controlPort, int appPort, List<String> views, EditHandler handler) throws IOException {
        this(controlPort, appPort, views, handler, false);
    }

    public StudioServer(int controlPort, int appPort, List<String> views, EditHandler handler,
                        boolean structuredPanel) throws IOException {
        this.appPort = appPort;
        server = HttpServer.create(new InetSocketAddress("localhost", controlPort), 0);
        String shell = shell(views, structuredPanel);

        // /health reports the live container port so the shell can re-frame after a hot reload.
        server.createContext("/health", exchange -> respond(exchange, 200, Integer.toString(this.appPort), "text/plain; charset=utf-8"));
        server.createContext("/edit", exchange -> {
            Map<String, String> form = parseForm(body(exchange));
            EditResult result = handler.edit(form.getOrDefault("view", ""), form.getOrDefault("text", ""));
            respond(exchange, result.ok() ? 200 : 400, result.message(), "text/plain; charset=utf-8");
        });
        server.createContext("/accept", exchange -> reply(exchange, handler.accept()));
        server.createContext("/reject", exchange -> reply(exchange, handler.reject()));

        // /spec reports a view's editable state as JSON; /set applies a deterministic structured
        // change (no model) — the control panel's read and write channels.
        server.createContext("/spec", exchange -> {
            Map<String, String> query = parseForm(rawQuery(exchange));
            String json = handler.spec(query.getOrDefault("view", ""));
            if (json == null) {
                respond(exchange, 400, "no editable spec for that view", "text/plain; charset=utf-8");
            } else {
                respond(exchange, 200, json, "application/json; charset=utf-8");
            }
        });
        server.createContext("/set", exchange -> {
            StructuredChange change;
            try {
                change = StructuredChange.fromForm(parseForm(body(exchange)));
            } catch (IllegalArgumentException e) {
                respond(exchange, 400, e.getMessage(), "text/plain; charset=utf-8");
                return;
            }
            reply(exchange, handler.apply(change));
        });
        server.createContext("/", exchange -> respond(exchange, 200, shell, "text/html; charset=utf-8"));
        server.setExecutor(null);
        server.start();
    }

    /** The bound control port (useful when constructed with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    /** Point the studio at a freshly launched container (after a reload); the shell re-frames it. */
    public void setAppPort(int appPort) {
        this.appPort = appPort;
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void reply(HttpExchange exchange, EditResult result) throws IOException {
        respond(exchange, result.ok() ? 200 : 400, result.message(), "text/plain; charset=utf-8");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String body(HttpExchange exchange) throws IOException {
        return new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static String rawQuery(HttpExchange exchange) {
        String query = exchange.getRequestURI().getRawQuery();
        return query == null ? "" : query;
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> form = new HashMap<>();
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            if (eq > 0) {
                form.put(URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8),
                        URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8));
            }
        }
        return form;
    }

    private static String shell(List<String> views, boolean structuredPanel) {
        String first = views.isEmpty() ? "" : views.get(0);
        StringBuilder nav = new StringBuilder();
        for (String view : views) {
            nav.append("<a onclick=\"select('").append(view).append("')\">").append(view).append("</a>\n");
        }
        return TEMPLATE.replace("%NAV%", nav.toString()).replace("%FIRST%", first)
                .replace("%PANEL%", structuredPanel ? PANEL_HTML : "")
                .replace("%PANELJS%", structuredPanel ? PANEL_JS : "function loadSpec() {}");
    }

    /** The control-panel aside (Phase 1): deterministic knobs for the selected view. */
    private static final String PANEL_HTML = """
            <aside class="panel" id="panel">
                <h3>Controls <span id="pname"></span></h3>
                <div id="pbody"><p class="hint">select a view</p></div>
              </aside>""";

    /** The panel's client logic: read /spec, render knobs, POST each change to /set. Uses event
     *  delegation with data- attributes so there are no inline handlers to quote-escape. */
    private static final String PANEL_JS = """
            let spec = null;
                async function loadSpec() {
                  const body = document.getElementById('pbody');
                  if (!body) return;
                  try {
                    const res = await fetch('/spec?view=' + encodeURIComponent(view));
                    if (!res.ok) { body.innerHTML = '<p class="hint">no editable spec for this view</p>'; return; }
                    spec = await res.json();
                    spec.order = (spec.columnOrder || spec.columns).slice();
                    document.getElementById('pname').textContent = spec.name;
                    renderPanel();
                  } catch (e) { body.innerHTML = '<p class="hint">' + e + '</p>'; }
                }
                function esc(s) {
                  return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/"/g, '&quot;');
                }
                function options(values, cur) {
                  return '<option value=""></option>' + values.map(v =>
                    '<option' + (v === cur ? ' selected' : '') + '>' + esc(v) + '</option>').join('');
                }
                function renderPanel() {
                  let h = '<div class="grp"><div class="lbl">Columns <em>verified</em></div><ul class="cols">';
                  spec.order.forEach((c, i) => h += '<li data-col="' + esc(c) + '"><span>' + esc(c) + '</span><span class="mv">'
                    + '<button data-mv="-1" data-i="' + i + '">&#8593;</button>'
                    + '<button data-mv="1" data-i="' + i + '">&#8595;</button></span></li>');
                  h += '</ul></div>';
                  h += '<div class="grp"><div class="lbl">Rows density <em>verified</em></div>'
                    + '<select data-op="setRowsStyle">' + options(spec.styles, spec.rowsStyle) + '</select></div>';
                  h += '<div class="grp"><div class="lbl">Table style <em>verified</em></div>'
                    + '<select data-op="setTableStyle">' + options(spec.styles, spec.tableStyle) + '</select></div>';
                  spec.actions.forEach(a => {
                    const cur = (spec.placements.find(p => p.label === a) || {}).region || '';
                    h += '<div class="grp"><div class="lbl">' + esc(a) + ' region <em>verified</em></div>'
                      + '<select data-region="' + esc(a) + '">' + options(spec.regions, cur) + '</select></div>';
                  });
                  document.getElementById('pbody').innerHTML = h;
                }
                function moveCol(i, d) {
                  const j = i + d; if (j < 0 || j >= spec.order.length) return;
                  const t = spec.order[i]; spec.order[i] = spec.order[j]; spec.order[j] = t;
                  setChange('setColumnOrder', 'columns=' + encodeURIComponent(spec.order.join(',')));
                }
                async function setChange(op, params) {
                  status('applying...');
                  const res = await fetch('/set', { method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
                    body: 'op=' + op + '&view=' + encodeURIComponent(view) + '&' + params });
                  status(await res.text());
                  shownPort = 0;
                  loadSpec();
                }
                document.addEventListener('click', e => {
                  const b = e.target.closest('[data-mv]');
                  if (b) moveCol(parseInt(b.getAttribute('data-i'), 10), parseInt(b.getAttribute('data-mv'), 10));
                });
                document.addEventListener('change', e => {
                  const t = e.target;
                  if (t.matches('[data-op]')) {
                    if (t.value) setChange(t.getAttribute('data-op'), 'value=' + encodeURIComponent(t.value));
                  } else if (t.matches('[data-region]')) {
                    const label = t.getAttribute('data-region');
                    if (t.value) setChange('setActionRegion',
                      'label=' + encodeURIComponent(label) + '&region=' + encodeURIComponent(t.value));
                    else setChange('clearActionRegion', 'label=' + encodeURIComponent(label));
                  }
                });
                function cssEsc(s) { return (window.CSS && CSS.escape) ? CSS.escape(s) : s; }
                window.addEventListener('message', e => {
                  if (e.origin !== 'http://localhost:' + shownPort) return;
                  const d = e.data || {};
                  if (d.type !== 'sky.select') return;
                  document.querySelectorAll('.panel .sel').forEach(x => x.classList.remove('sel'));
                  let target = null;
                  if (d.control) {
                    const sel = document.querySelector('.panel [data-region="' + cssEsc(d.control) + '"]');
                    if (sel) target = sel.closest('.grp');
                  } else if (d.field) {
                    document.querySelectorAll('.panel li[data-col]').forEach(li => {
                      if ((li.getAttribute('data-col') || '').toLowerCase() === d.field.toLowerCase()) target = li;
                    });
                  }
                  if (target) { target.classList.add('sel'); target.scrollIntoView({ block: 'nearest' }); }
                });""";

    private static final String TEMPLATE = """
            <!doctype html>
            <html lang="en">
            <head>
              <meta charset="utf-8"/>
              <title>SkyLang preview</title>
              <style>
                body { margin: 0; font-family: system-ui, sans-serif; display: flex; height: 100vh; }
                nav { width: 200px; background: #0d3b3b; color: #cfeaea; padding: 1rem 0.75rem; }
                nav h3 { margin: 0 0 0.75rem; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; }
                nav a { display: block; color: #cfeaea; padding: 0.4rem 0.5rem; cursor: pointer; border-radius: 4px; }
                nav a:hover { background: rgba(255, 255, 255, 0.12); }
                main { flex: 1; display: flex; flex-direction: column; }
                iframe { flex: 1; border: 0; background: #fff; }
                .edit { display: flex; gap: 0.5rem; align-items: center; padding: 0.5rem 0.75rem; background: #eef2f2; border-top: 1px solid #cdd; }
                .edit input { flex: 1; padding: 0.45rem 0.6rem; border: 1px solid #bcc; border-radius: 4px; font: inherit; }
                .edit button { padding: 0.45rem 0.9rem; border: 0; border-radius: 4px; cursor: pointer; background: #0d3b3b; color: #fff; }
                .edit button.ghost { background: #cdd6d6; color: #143; }
                .status { min-width: 16ch; font-size: 0.8rem; color: #345; }
                .panel { width: 264px; background: #f6f8f8; border-left: 1px solid #cdd; padding: 0.75rem; overflow: auto; font-size: 0.85rem; }
                .panel h3 { margin: 0 0 0.6rem; font-size: 0.75rem; text-transform: uppercase; letter-spacing: 0.08em; color: #345; }
                .panel .grp { margin-bottom: 0.8rem; }
                .panel .lbl { color: #234; margin-bottom: 0.25rem; }
                .panel .lbl em { font-style: normal; font-size: 0.68rem; color: #2a7d5a; background: #dff3e8; padding: 0 0.3rem; border-radius: 3px; margin-left: 0.25rem; }
                .panel select { width: 100%; padding: 0.3rem; border: 1px solid #bcc; border-radius: 4px; font: inherit; }
                .panel ul.cols { list-style: none; margin: 0; padding: 0; }
                .panel ul.cols li { display: flex; justify-content: space-between; align-items: center; padding: 0.2rem 0.4rem; background: #fff; border: 1px solid #dde; border-radius: 4px; margin-bottom: 0.2rem; }
                .panel .mv button { border: 0; background: #e3eaea; border-radius: 3px; cursor: pointer; margin-left: 0.15rem; padding: 0.1rem 0.35rem; }
                .panel .hint { color: #789; }
                .panel .sel { outline: 2px solid #2a7d5a; outline-offset: 1px; border-radius: 4px; }
              </style>
            </head>
            <body>
              <nav>
                <h3>Views</h3>
                %NAV%
              </nav>
              <main>
                <iframe id="view"></iframe>
                <div class="edit">
                  <input id="instruction" type="text" placeholder="Describe a change — e.g. move Restock into a toolbar"/>
                  <button onclick="send()">Send</button>
                  <button class="ghost" onclick="post('/accept')">Accept</button>
                  <button class="ghost" onclick="post('/reject')">Reject</button>
                  <span class="status" id="status"></span>
                </div>
              </main>
              %PANEL%
              <script>
                let view = "%FIRST%";
                let shownPort = 0;
                function select(next) { view = next; shownPort = 0; loadSpec(); }
                function status(msg) { document.getElementById('status').textContent = msg; }
                async function refresh() {
                  try {
                    const port = parseInt(await (await fetch('/health')).text(), 10);
                    if (port && port !== shownPort) {
                      shownPort = port;
                      document.getElementById('view').src = 'http://localhost:' + port + '/app/' + view + '.xhtml';
                    }
                  } catch (e) {
                    shownPort = 0;
                  }
                  setTimeout(refresh, 1000);
                }
                async function send() {
                  const text = document.getElementById('instruction').value;
                  status('working...');
                  const body = 'view=' + encodeURIComponent(view) + '&text=' + encodeURIComponent(text);
                  const res = await fetch('/edit', { method: 'POST',
                    headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body });
                  status(await res.text());
                  shownPort = 0;
                }
                async function post(path) {
                  status('working...');
                  const res = await fetch(path, { method: 'POST' });
                  status(await res.text());
                  shownPort = 0;
                }
                %PANELJS%
                refresh();
                loadSpec();
              </script>
            </body>
            </html>
            """;
}
