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
import java.net.http.HttpResponse;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StudioServerTest {

    @Test
    void servesTheShellAndHealth() throws Exception {
        try (StudioServer studio = new StudioServer(0, 12345, List.of("ProductList"))) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://localhost:" + studio.port();

            HttpResponse<String> shell = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, shell.statusCode());
            assertTrue(shell.body().contains("ProductList"), "the shell should list the view");
            assertTrue(shell.body().contains("12345"), "the shell should frame the container port");

            HttpResponse<String> health = http.send(
                    HttpRequest.newBuilder(URI.create(base + "/health")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals("ok", health.body().strip());
        }
    }
}
