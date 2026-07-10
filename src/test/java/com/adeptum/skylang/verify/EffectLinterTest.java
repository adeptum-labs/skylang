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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectLinterTest {

    @Test
    void acceptsABodyThatStaysInsideItsBudget() {
        assertTrue(EffectLinter.violations("""
                db.save(c);
                return db.save(new Order(1, c, Status.Open, java.util.List.of(), clock.instant(), total));
                """, List.of("db", "clock")).isEmpty());
    }

    @Test
    void flagsRawTimeApisEvenWithTheClockBudget() {
        List<String> violations = EffectLinter.violations(
                "return java.time.Instant.now();", List.of("clock"));
        assertTrue(violations.stream().anyMatch(v -> v.contains("clock.instant()")), violations.toString());
    }

    @Test
    void flagsAnEffectMissingFromTheBudget() {
        List<String> violations = EffectLinter.violations(
                "var json = new java.net.URL(url).getContent();", List.of("db"));
        assertTrue(violations.stream().anyMatch(v -> v.contains("http")), violations.toString());
    }

    @Test
    void flagsCapabilitiesNoEffectBinds() {
        assertTrue(!EffectLinter.violations("java.nio.file.Files.readString(p);", List.of()).isEmpty());
        assertTrue(!EffectLinter.violations("Runtime.getRuntime().exec(cmd);", List.of()).isEmpty());
    }

    @Test
    void flagsRawPersistenceAndMailApis() {
        assertTrue(!EffectLinter.violations("java.sql.DriverManager.getConnection(u);", List.of("db")).isEmpty());
        assertTrue(!EffectLinter.violations("jakarta.mail.Transport.send(m);", List.of()).isEmpty());
    }
}
