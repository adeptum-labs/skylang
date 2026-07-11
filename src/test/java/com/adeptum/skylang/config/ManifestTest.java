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

package com.adeptum.skylang.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The project manifest is one profile line away from a retarget, so its parse must be
 * boring and its errors precise.
 */
class ManifestTest {

    @Test
    void parsesProjectAndProfile() {
        Manifest m = Manifest.parse("""
                // SkyLang project manifest.
                project shop
                profile jvm-jakarta        // change this one line to ts-node, or python
                """);
        assertEquals("shop", m.project());
        assertEquals("jvm-jakarta", m.profile());
    }

    @Test
    void profileDefaultsToTheReferenceProfileWhenAbsent() {
        Manifest m = Manifest.parse("project shop\n");
        assertEquals("jvm-jakarta", m.profile());
    }

    @Test
    void rejectsAnUnknownDirective() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> Manifest.parse("project shop\nflavour vanilla\n"));
        assertTrue(e.getMessage().contains("flavour"), e.getMessage());
    }

    @Test
    void rejectsAProfileThatIsNotAPlainName() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> Manifest.parse("project shop\nprofile ../../etc\n"));
        assertTrue(e.getMessage().contains("plain name"), e.getMessage());
    }

    @Test
    void rejectsADuplicateProfileLine() {
        ConfigException e = assertThrows(ConfigException.class,
                () -> Manifest.parse("project shop\nprofile a\nprofile b\n"));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
    }

    @Test
    void parsesTheRequiresBlock() {
        Manifest m = Manifest.parse("""
                project shop
                profile jvm-jakarta

                requires {
                  bcrypt      ^4.0     // any compatible 4.x
                  http-client ~2.1     // patch-level updates within 2.1
                  json        2.1.3    // pinned exactly
                }
                """);
        assertEquals(3, m.requires().size());
        assertEquals(new Manifest.Require("bcrypt", "^4.0"), m.requires().get(0));
        assertEquals(new Manifest.Require("http-client", "~2.1"), m.requires().get(1));
        assertEquals(new Manifest.Require("json", "2.1.3"), m.requires().get(2));
    }

    @Test
    void aMissingRequiresBlockMeansNoDependencies() {
        assertTrue(Manifest.parse("project shop\n").requires().isEmpty());
    }

    @Test
    void rejectsADuplicateRequirement() {
        ConfigException e = assertThrows(ConfigException.class, () -> Manifest.parse("""
                project shop
                requires {
                  bcrypt ^4.0
                  bcrypt ^4.1
                }
                """));
        assertTrue(e.getMessage().contains("duplicate"), e.getMessage());
        assertTrue(e.getMessage().contains("bcrypt"), e.getMessage());
    }

    @Test
    void rejectsAMalformedVersionConstraint() {
        ConfigException e = assertThrows(ConfigException.class, () -> Manifest.parse("""
                project shop
                requires {
                  bcrypt latest
                }
                """));
        assertTrue(e.getMessage().contains("latest"), e.getMessage());
    }

    @Test
    void loadsFromTheDirectoryNextToTheSource(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("sky.project"), "project tokens\nprofile ts-node\n");
        assertEquals("ts-node", Manifest.load(dir).orElseThrow().profile());
        assertTrue(Manifest.load(dir.resolve("nowhere")).isEmpty(),
                "a directory without a manifest yields empty");
    }
}
