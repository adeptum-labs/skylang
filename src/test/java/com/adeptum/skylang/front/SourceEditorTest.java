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

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SourceEditorTest {

    private static final String SRC = """
            module shop

            entity Product { id Int  name Text  stock Int @min(0) }

            service Catalog {
              all() -> [Product]  intent "all"
              restock(p Product, n Int) -> Product  intent "r"
            }

            view ProductList at "/products" {
              shows Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row, ask Int)
            }
            """;

    @Test
    void insertsAppearsBeforeTheClosingBrace() {
        String edited = SourceEditor.addAppears(SRC, "ProductList",
                List.of("appears action \"Restock\" in toolbar", "appears rows is compact"));

        // The edited source parses and now carries the appears predicates.
        Ast.View view = Parsing.parse(edited, "shop.sky").views().get(0);
        assertEquals(2, view.appears().size());

        // The appears are indented like the other clauses (two spaces).
        assertTrue(edited.contains("\n  appears action \"Restock\" in toolbar\n"), edited);
        assertTrue(edited.contains("\n  appears rows is compact\n"), edited);

        // Everything up to the edited view is byte-identical.
        int viewIdx = SRC.indexOf("view ProductList");
        assertEquals(SRC.substring(0, viewIdx), edited.substring(0, viewIdx));
    }

    @Test
    void emptyLinesLeaveTheSourceUnchanged() {
        assertEquals(SRC, SourceEditor.addAppears(SRC, "ProductList", List.of()));
    }
}
