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
        this.appPort = appPort;
        server = HttpServer.create(new InetSocketAddress("localhost", controlPort), 0);
        String shell = shell(views);

        // /health reports the live container port so the shell can re-frame after a hot reload.
        server.createContext("/health", exchange -> respond(exchange, 200, Integer.toString(this.appPort), "text/plain; charset=utf-8"));
        server.createContext("/edit", exchange -> {
            Map<String, String> form = parseForm(body(exchange));
            EditResult result = handler.edit(form.getOrDefault("view", ""), form.getOrDefault("text", ""));
            respond(exchange, result.ok() ? 200 : 400, result.message(), "text/plain; charset=utf-8");
        });
        server.createContext("/accept", exchange -> reply(exchange, handler.accept()));
        server.createContext("/reject", exchange -> reply(exchange, handler.reject()));
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

    private static String shell(List<String> views) {
        String first = views.isEmpty() ? "" : views.get(0);
        StringBuilder nav = new StringBuilder();
        for (String view : views) {
            nav.append("<a onclick=\"select('").append(view).append("')\">").append(view).append("</a>\n");
        }
        return TEMPLATE.replace("%NAV%", nav.toString()).replace("%FIRST%", first);
    }

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
              <script>
                let view = "%FIRST%";
                let shownPort = 0;
                function select(next) { view = next; shownPort = 0; }
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
                refresh();
              </script>
            </body>
            </html>
            """;
}
