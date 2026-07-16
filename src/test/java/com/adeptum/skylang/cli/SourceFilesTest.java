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

import com.adeptum.skylang.config.ConfigException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Bare commands find the project's one source file; anything ambiguous is a precise refusal. */
class SourceFilesTest {

    @Test
    void anExplicitPathWins(@TempDir Path dir) {
        Path given = dir.resolve("elsewhere.sky");
        assertEquals(given, SourceFiles.resolve(given, dir));
    }

    @Test
    void theSoleSkyFileIsTheProjectSource(@TempDir Path dir) throws IOException {
        Path shop = Files.writeString(dir.resolve("shop.sky"), "module shop\n");
        Files.writeString(dir.resolve("sky.project"), "project shop\n");
        assertEquals(shop, SourceFiles.resolve(null, dir));
    }

    @Test
    void zeroOrSeveralSourcesAreRefusedByName(@TempDir Path dir) throws IOException {
        ConfigException none = assertThrows(ConfigException.class, () -> SourceFiles.resolve(null, dir));
        assertTrue(none.getMessage().contains("no .sky file"), none.getMessage());

        Files.writeString(dir.resolve("a.sky"), "module a\n");
        Files.writeString(dir.resolve("b.sky"), "module b\n");
        ConfigException many = assertThrows(ConfigException.class, () -> SourceFiles.resolve(null, dir));
        assertTrue(many.getMessage().contains("a.sky, b.sky"), many.getMessage());
    }

    @Test
    void severalFilesOfOneModuleResolveToTheFirst(@TempDir Path dir) throws IOException {
        Path domain = Files.writeString(dir.resolve("a-domain.sky"), "module shop\n");
        Files.writeString(dir.resolve("b-pages.sky"), "module shop\n");
        assertEquals(domain, SourceFiles.resolve(null, dir));
    }
}
