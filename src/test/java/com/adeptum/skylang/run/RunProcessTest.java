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

package com.adeptum.skylang.run;

import com.adeptum.skylang.backend.RunPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The runner knows nothing about Maven or wars — it executes a plan and watches for the marker.
 * That is what makes it testable with a shell script standing in for the application.
 */
@EnabledOnOs({OS.LINUX, OS.MAC})
class RunProcessTest {

    private final ByteArrayOutputStream out = new ByteArrayOutputStream();

    private static RunPlan plan(String script) {
        return new RunPlan(List.of("sh", "-c", script), Path.of("."), Path.of("target/app.war"),
                "SKY RUNNING app=", "/");
    }

    private String console() {
        return out.toString(StandardCharsets.UTF_8);
    }

    @Test
    void learnsThePortFromTheMarkerAndStreamsTheApplicationLog() throws Exception {
        RunPlan plan = plan("echo 'building, not the app talking'; "
                + "echo 'SKY RUNNING app=4711'; echo 'a request arrived'; sleep 30");

        try (RunProcess app = RunProcess.launch(plan, Duration.ofSeconds(20),
                new PrintStream(out, true, StandardCharsets.UTF_8))) {
            assertEquals(4711, app.port());
            assertTrue(app.isAlive());

            waitForConsole("a request arrived");
            assertTrue(console().contains("a request arrived"),
                    "once it is serving, the application's log is the user's log: " + console());
            assertFalse(console().contains("not the app talking"),
                    "the noise of getting there is not: " + console());
        }
    }

    @Test
    void anApplicationThatDiesBeforeServingExplainsWhy() {
        RunPlan plan = plan("echo 'SKY RUN ERROR port 8080 is already in use'; exit 1");

        IOException e = assertThrows(IOException.class, () -> RunProcess.launch(plan, Duration.ofSeconds(20),
                new PrintStream(out, true, StandardCharsets.UTF_8)));

        assertTrue(e.getMessage().contains("stopped before it was serving"), e.getMessage());
        assertTrue(e.getMessage().contains("port 8080 is already in use"),
                "the tail of the output is the only clue to why: " + e.getMessage());
    }

    @Test
    void anApplicationThatDiesOnItsOwnIsACrash() throws Exception {
        RunProcess app = RunProcess.launch(plan("echo 'SKY RUNNING app=4711'; exit 3"),
                Duration.ofSeconds(20), new PrintStream(out, true, StandardCharsets.UTF_8));
        CountDownLatch crashed = new CountDownLatch(1);

        app.onCrash(crashed::countDown);

        assertTrue(crashed.await(20, TimeUnit.SECONDS),
                "an application that exits by itself must not leave the run hanging");
    }

    @Test
    void stoppingTheApplicationDeliberatelyIsNotACrash() throws Exception {
        RunProcess app = RunProcess.launch(plan("echo 'SKY RUNNING app=4711'; sleep 30"),
                Duration.ofSeconds(20), new PrintStream(out, true, StandardCharsets.UTF_8));
        CountDownLatch crashed = new CountDownLatch(1);
        app.onCrash(crashed::countDown);

        app.close();   // what a relaunch and a Ctrl-C both do

        assertFalse(crashed.await(2, TimeUnit.SECONDS),
                "a relaunch stops the old application on purpose; that is not a crash");
    }

    @Test
    void closingStopsTheApplication() throws Exception {
        RunProcess app = RunProcess.launch(plan("echo 'SKY RUNNING app=4711'; sleep 30"),
                Duration.ofSeconds(20), new PrintStream(out, true, StandardCharsets.UTF_8));

        app.close();

        assertFalse(app.isAlive(), "the port must be free again when the run ends");
    }

    /** The reader thread forwards asynchronously; give it a moment rather than racing it. */
    private void waitForConsole(String expected) throws InterruptedException {
        for (int i = 0; i < 100 && !console().contains(expected); i++) {
            Thread.sleep(50);
        }
    }
}
