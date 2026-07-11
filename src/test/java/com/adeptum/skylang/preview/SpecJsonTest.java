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

package com.adeptum.skylang.preview;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** The panel's read channel: a view's editable state folded into JSON. */
class SpecJsonTest {

    private static final String SRC = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              all() -> [Product]  intent "all"
              restock(id Int) -> Product  intent "r"
            }
            view ProductList at "/products" {
              shows Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row)
              appears columns (stock, name)
              appears rows is compact
              appears action "Restock" in toolbar
            }
            """;

    @Test
    void serializesColumnsActionsAndFoldedAppears() {
        Ast.View view = Parsing.parse(SRC, "shop.sky").views().get(0);
        String json = SpecJson.of(view);

        assertTrue(json.contains("\"name\":\"ProductList\""), json);
        assertTrue(json.contains("\"columns\":[\"name\",\"stock\"]"), json);
        assertTrue(json.contains("\"actions\":[\"Restock\"]"), json);
        assertTrue(json.contains("\"columnOrder\":[\"stock\",\"name\"]"), json);
        assertTrue(json.contains("\"rowsStyle\":\"compact\""), json);
        assertTrue(json.contains("\"tableStyle\":null"), json);
        assertTrue(json.contains("\"placements\":[{\"label\":\"Restock\",\"region\":\"toolbar\"}]"), json);
        assertTrue(json.contains("\"regions\":[\"toolbar\",\"row\",\"footer\"]"), json);
        assertTrue(json.contains("\"styles\":[\"compact\",\"striped\",\"bordered\"]"), json);
    }
}
