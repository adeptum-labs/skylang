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

package com.adeptum.skylang.verify;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds a synthesized body to its service's effects budget. The budget is structurally
 * enforced by the handles that exist on the staged service; this linter additionally
 * rejects the raw platform APIs a body could reach without any handle — outbound
 * networking, storage, mail, wall-clock time, files, and processes.
 */
public final class EffectLinter {

    private record Rule(String token, String effect, String hint) {
    }

    private static final List<Rule> RULES = List.of(
            new Rule("Instant.now(", "clock", "read the time via clock.instant()"),
            new Rule("System.currentTimeMillis", "clock", "read the time via clock.instant()"),
            new Rule("System.nanoTime", "clock", "read the time via clock.instant()"),
            new Rule("new java.util.Date", "clock", "read the time via clock.instant()"),
            new Rule("LocalDate.now(", "clock", "read the time via clock.instant()"),
            new Rule("LocalDateTime.now(", "clock", "read the time via clock.instant()"),
            new Rule("SkyClock", "clock", "SkyClock backs field defaults; bodies read clock.instant()"),
            new Rule("java.net", "http", "outbound requests go through http.get(...)"),
            new Rule("java.sql", "db", "storage goes through the db store"),
            new Rule("javax.sql", "db", "storage goes through the db store"),
            new Rule("jakarta.persistence", "db", "storage goes through the db store"),
            new Rule("jakarta.mail", "mail", "mail goes through mail.send(...)"),
            new Rule("javax.mail", "mail", "mail goes through mail.send(...)"),
            new Rule("java.io.", null, "file io has no effect binding"),
            new Rule("java.nio.file", null, "file io has no effect binding"),
            new Rule("ProcessBuilder", null, "process execution has no effect binding"),
            new Rule("Runtime.getRuntime", null, "process execution has no effect binding"));

    private EffectLinter() {
    }

    /** The budget violations in one synthesized body; empty when the body may be staged. */
    public static List<String> violations(String body, List<String> uses) {
        List<String> found = new ArrayList<>();
        for (Rule rule : RULES) {
            if (!body.contains(rule.token())) {
                continue;
            }
            String message = rule.effect() != null && !uses.contains(rule.effect())
                    ? "'" + rule.effect() + "' is not in the service's uses budget"
                    : rule.hint();
            found.add(rule.token() + ": " + message);
        }
        return found;
    }
}
