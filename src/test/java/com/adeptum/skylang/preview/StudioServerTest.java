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

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpRequest.BodyPublishers;
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioServerTest {

    private static final class RecordingHandler implements EditHandler {
        String view;
        String instruction;
        boolean accepted;
        boolean rejected;

        @Override
        public EditResult edit(String view, String instruction) {
            this.view = view;
            this.instruction = instruction;
            return EditResult.ok("applied");
        }

        @Override
        public EditResult accept() {
            accepted = true;
            return EditResult.ok("saved");
        }

        @Override
        public EditResult reject() {
            rejected = true;
            return EditResult.ok("reverted");
        }
    }

    @Test
    void servesTheShellAndHealth() throws Exception {
        try (StudioServer studio = new StudioServer(0, 12345, List.of("ProductList"), EditHandler.NONE)) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + studio.port();

            HttpResponse<String> shell = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, shell.statusCode());
            assertTrue(shell.body().contains("ProductList"), "the shell should list the view");
            assertTrue(shell.body().contains("Send"), "the shell should carry the edit panel");

            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("12345", health.body().strip());
        }
    }

    @Test
    void delegatesEditActionsToTheHandler() throws Exception {
        RecordingHandler handler = new RecordingHandler();
        try (StudioServer studio = new StudioServer(0, 0, List.of("V"), handler)) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + studio.port();

            HttpResponse<String> edit = http.send(HttpRequest.newBuilder(URI.create(base + "/edit"))
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .POST(BodyPublishers.ofString("view=V&text=move+it+to+a+toolbar")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, edit.statusCode());
            assertEquals("applied", edit.body());
            assertEquals("V", handler.view);
            assertEquals("move it to a toolbar", handler.instruction);

            http.send(HttpRequest.newBuilder(URI.create(base + "/accept")).POST(BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(handler.accepted, "accept should reach the handler");

            http.send(HttpRequest.newBuilder(URI.create(base + "/reject")).POST(BodyPublishers.noBody()).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertTrue(handler.rejected, "reject should reach the handler");
        }
    }
}
