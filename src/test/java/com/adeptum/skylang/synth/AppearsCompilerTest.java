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

package com.adeptum.skylang.synth;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AppearsCompilerTest {

    private static Ast.View view() {
        String src = """
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                  restock(p Product, n Int) -> Product  intent "restock"
                }
                view V {
                  shows Catalog.all() as a table of (name, stock)
                  action "Restock" on row -> Catalog.restock(row, ask Int)
                }
                """;
        return Parsing.parse(src, "v.sky").views().get(0);
    }

    @Test
    void extractsAppearsLinesFromReply() {
        Llm stub = (system, user) -> "Sure:\nappears action \"Restock\" in toolbar\nappears rows is compact\nDone.";
        List<String> lines = new AppearsCompiler(stub).compile(view(), "toolbar and dense");
        assertEquals(List.of("appears action \"Restock\" in toolbar", "appears rows is compact"), lines);
    }

    @Test
    void userPromptCarriesInstructionAndContext() {
        String[] captured = new String[1];
        Llm stub = (system, user) -> {
            captured[0] = user;
            return "";
        };
        new AppearsCompiler(stub).compile(view(), "put restock in a toolbar");
        assertTrue(captured[0].contains("put restock in a toolbar"), "the instruction should reach the model");
        assertTrue(captured[0].contains("Restock"), "the view's actions should give the model valid labels");
    }
}
