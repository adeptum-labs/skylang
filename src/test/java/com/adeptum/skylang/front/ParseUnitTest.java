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

package com.adeptum.skylang.front;

import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** A module may span several .sky files; the module header is what groups them. */
class ParseUnitTest {

    @Test
    void mergesSameModuleSiblingsInFilenameOrder(@TempDir Path dir) throws IOException {
        Path domain = Files.writeString(dir.resolve("a-domain.sky"), """
                module shop
                entity Product { id Int @id  name Text  stock Int @min(0) }
                """);
        Files.writeString(dir.resolve("b-services.sky"), """
                module shop
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                """);
        Files.writeString(dir.resolve("c-pages.sky"), """
                module shop
                view ProductList at "/products" {
                  shows Catalog.all() as a table of (name, stock)
                }
                """);

        Ast.Module m = Parsing.parseUnit(domain);
        assertEquals("shop", m.name());
        assertEquals(1, m.entities().size());
        assertEquals(1, m.services().size());
        assertEquals(1, m.views().size());
    }

    @Test
    void anchoringOnALaterFileYieldsTheSameModule(@TempDir Path dir) throws IOException {
        Files.writeString(dir.resolve("a-domain.sky"), """
                module shop
                entity Product { id Int @id  name Text }
                """);
        Path pages = Files.writeString(dir.resolve("b-services.sky"), """
                module shop
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                """);

        Ast.Module m = Parsing.parseUnit(pages);
        assertEquals(1, m.entities().size());
        assertEquals(1, m.services().size());
    }

    @Test
    void ignoresSiblingsDeclaringOtherModules(@TempDir Path dir) throws IOException {
        Path shop = Files.writeString(dir.resolve("shop.sky"), """
                module shop
                entity Product { id Int @id  name Text }
                """);
        Files.writeString(dir.resolve("bank.sky"), """
                module bank
                entity Account { id Int @id  iban Text }
                """);

        Ast.Module m = Parsing.parseUnit(shop);
        assertEquals("shop", m.name());
        assertEquals(1, m.entities().size());
        assertEquals("Product", m.entities().get(0).name());
    }

    @Test
    void rejectsTheSameDeclarationNameInTwoFiles(@TempDir Path dir) throws IOException {
        Path first = Files.writeString(dir.resolve("a.sky"), """
                module shop
                entity Product { id Int @id  name Text }
                """);
        Files.writeString(dir.resolve("b.sky"), """
                module shop
                entity Product { id Int @id  title Text }
                """);

        SkyParseException e = assertThrows(SkyParseException.class, () -> Parsing.parseUnit(first));
        assertTrue(e.getMessage().contains("Product"), e.getMessage());
        assertTrue(e.getMessage().contains("a.sky") && e.getMessage().contains("b.sky"),
                e.getMessage());
    }

    @Test
    void aSingleFileUnitParsesExactlyAsTheFileDoes(@TempDir Path dir) throws IOException {
        Path shop = Files.writeString(dir.resolve("shop.sky"), """
                module shop
                entity Product { id Int @id  name Text }
                """);
        assertEquals(Parsing.parseFile(shop).toString(), Parsing.parseUnit(shop).toString());
    }
}
