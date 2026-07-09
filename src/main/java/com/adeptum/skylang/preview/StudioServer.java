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
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * The studio shell: a lightweight JDK HTTP server the native CLI can host, which frames the
 * live-rendered views served by the embedded container in a separate subprocess.
 */
public final class StudioServer implements AutoCloseable {

    private final HttpServer server;

    public StudioServer(int controlPort, int appPort, List<String> views) throws IOException {
        server = HttpServer.create(new InetSocketAddress("localhost", controlPort), 0);
        String shell = shell(appPort, views);
        server.createContext("/health", exchange -> respond(exchange, "ok", "text/plain; charset=utf-8"));
        server.createContext("/", exchange -> respond(exchange, shell, "text/html; charset=utf-8"));
        server.setExecutor(null);
        server.start();
    }

    /** The bound control port (useful when constructed with port 0). */
    public int port() {
        return server.getAddress().getPort();
    }

    @Override
    public void close() {
        server.stop(0);
    }

    private static void respond(HttpExchange exchange, String body, String contentType) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    private static String shell(int appPort, List<String> views) {
        String first = views.isEmpty() ? "" : views.get(0);
        StringBuilder nav = new StringBuilder();
        for (String view : views) {
            nav.append("<a onclick=\"show('").append(view).append("')\">").append(view).append("</a>\n");
        }
        return TEMPLATE
                .replace("%APP%", Integer.toString(appPort))
                .replace("%NAV%", nav.toString())
                .replace("%FIRST%", first);
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
                iframe { flex: 1; border: 0; background: #fff; }
              </style>
            </head>
            <body>
              <nav>
                <h3>Views</h3>
                %NAV%
              </nav>
              <iframe id="view" src="http://localhost:%APP%/app/%FIRST%.xhtml"></iframe>
              <script>
                function show(view) {
                  document.getElementById('view').src = 'http://localhost:%APP%/app/' + view + '.xhtml';
                }
              </script>
            </body>
            </html>
            """;
}
