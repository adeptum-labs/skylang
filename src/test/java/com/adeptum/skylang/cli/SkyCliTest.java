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

package com.adeptum.skylang.cli;

import org.junit.jupiter.api.Test;
import picocli.CommandLine;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every subcommand should answer {@code --help} by printing its help and exiting cleanly, not by
 * reporting an unknown option. This guards that each command mixes in the standard help option.
 */
class SkyCliTest {

    private static final List<String> SUBCOMMANDS = List.of(
            "onboard", "check", "build", "preview", "tdd", "freeze", "why", "test", "clean");

    @Test
    void everySubcommandPrintsHelpAndExitsZero() {
        for (String command : SUBCOMMANDS) {
            PrintStream original = System.out;
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            System.setOut(new PrintStream(buffer, true, StandardCharsets.UTF_8));
            int code;
            try {
                code = new CommandLine(new SkyCli()).execute(command, "--help");
            } finally {
                System.setOut(original);
            }
            String out = buffer.toString(StandardCharsets.UTF_8);
            assertEquals(0, code, "`sky " + command + " --help` must exit 0, not error");
            assertTrue(out.contains("Usage: sky " + command),
                    "`sky " + command + " --help` must print its usage:\n" + out);
        }
    }

    @Test
    void theShortHelpFlagWorksToo() {
        int code = new CommandLine(new SkyCli()).execute("onboard", "-h");
        assertEquals(0, code, "-h must also print help and exit 0");
    }
}
