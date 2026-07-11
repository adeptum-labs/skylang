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

package com.adeptum.skylang.freeze;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LockTest {

    @Test
    void bodiesAreStoredLineByLineForReviewableDiffs(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sky.lock");
        Lock lock = new Lock();
        lock.setProfile("jvm-jakarta", "0.1.0");
        lock.put("shop.Catalog.restock", new Lock.Entry("abc123",
                "long updated = product.stock() + units;\nreturn product.withStock(updated);"));
        lock.save(path);

        String text = Files.readString(path);
        assertTrue(text.contains("\"long updated = product.stock() + units;\",\n"),
                "each body line must sit on its own line of the lock");
        assertTrue(text.lines().count() > 10, "the lock must be pretty-printed, not one line");

        Lock reloaded = Lock.load(path);
        assertEquals("long updated = product.stock() + units;\nreturn product.withStock(updated);",
                reloaded.get("shop.Catalog.restock").orElseThrow().body());
    }

    @Test
    void pinnedDependenciesRoundTripThroughTheHeader(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sky.lock");
        Lock lock = new Lock();
        lock.setProfile("jvm-jakarta", "0.1.0");
        lock.setDeps(java.util.Map.of("bcrypt",
                new Lock.Dep("^4.0", "4.0.2", java.util.List.of("org.mindrot:jbcrypt:0.4"))));
        lock.save(path);

        Lock reloaded = Lock.load(path);
        Lock.Dep bcrypt = reloaded.deps().get("bcrypt");
        assertEquals("^4.0", bcrypt.requested());
        assertEquals("4.0.2", bcrypt.version());
        assertEquals(java.util.List.of("org.mindrot:jbcrypt:0.4"), bcrypt.coordinates());
    }

    @Test
    void aLockWithoutDependenciesHasNoDepsHeader(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sky.lock");
        Lock lock = new Lock();
        lock.setProfile("jvm-jakarta", "0.1.0");
        lock.save(path);
        assertTrue(!Files.readString(path).contains("\"deps\""),
                "locks written before the requires block existed must keep their exact shape");
    }

    @Test
    void legacyStringBodiesStillLoad(@TempDir Path dir) throws Exception {
        Path path = dir.resolve("sky.lock");
        Files.writeString(path, """
                {"profile":{"id":"jvm-jakarta","version":"0.1.0"},
                 "methods":{"shop.Catalog.restock":{"specHash":"abc","body":"return null;"}},
                 "views":{}}
                """);
        Lock lock = Lock.load(path);
        assertEquals("return null;", lock.get("shop.Catalog.restock").orElseThrow().body());
    }

    @Test
    void canonicalFormNeverDiffsOnWhitespace() {
        assertEquals("if (x) {\n    return 1;\n}",
                Lock.canonical("if (x) {   \r\n\treturn 1;\r\n}\n\n"));
        assertEquals("a\n\nb", Lock.canonical("\n\na\n\nb\n"));
    }
}
