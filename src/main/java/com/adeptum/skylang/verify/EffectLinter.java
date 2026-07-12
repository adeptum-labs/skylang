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
            new Rule("jakarta.security", "auth", "read the principal via auth.currentPrincipal()"),
            new Rule("getUserPrincipal", "auth", "read the principal via auth.currentPrincipal()"),
            new Rule("SkyAuth", "auth", "SkyAuth backs the auth binding; bodies read auth.currentPrincipal()"),
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

    /**
     * The dependency analogue of the effects rule: a body — synthesized or native — may
     * reference a registry-known package only when the manifest requires its logical name.
     * The registry is the only door; nothing enters through the side.
     */
    public static List<String> dependencyViolations(String body, com.adeptum.skylang.deps.Budget deps) {
        List<String> found = new ArrayList<>();
        deps.knownPrefixes().forEach((prefix, name) -> {
            if (body.contains(prefix) && !deps.declares(name)) {
                found.add("dependency '" + name + "' used but not declared in requires (found '"
                        + prefix + "')");
            }
        });
        return found;
    }

    private static final List<String> LOGGER_TOKENS =
            List.of("log.", "logger.", "Logger", "System.out", "System.err");

    /** Budget violations plus the module's forbid-policies, checked line by line. */
    public static List<String> violations(String body, List<String> uses,
                                          com.adeptum.skylang.front.ast.Ast.Module module) {
        List<String> found = new ArrayList<>(violations(body, uses));
        for (var policy : module.policies()) {
            if (!(policy.whenever() instanceof com.adeptum.skylang.front.ast.Ast.PassedToLogger)
                    || !(policy.rule() instanceof com.adeptum.skylang.front.ast.Ast.ForbidRule)) {
                continue;
            }
            List<String> secretReads = secretReads(module);
            for (String line : body.split("\n")) {
                boolean logs = LOGGER_TOKENS.stream().anyMatch(line::contains);
                boolean secret = line.contains(".reveal(") || secretReads.stream().anyMatch(line::contains);
                if (logs && secret) {
                    found.add("policy " + policy.name() + ": a Secret must never reach a logger — "
                            + line.strip());
                }
            }
        }
        return found;
    }

    /** The accessor calls that read a Secret-typed field anywhere in the module. */
    private static List<String> secretReads(com.adeptum.skylang.front.ast.Ast.Module module) {
        List<String> reads = new ArrayList<>();
        for (var entity : module.entities()) {
            for (var field : entity.fields()) {
                if (field.type() instanceof com.adeptum.skylang.front.ast.Ast.GenericType g
                        && g.name().equals("Secret")) {
                    reads.add("." + field.name() + "()");
                }
            }
        }
        return reads;
    }
}
