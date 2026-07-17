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
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that developer-defined annotations on services, methods, and entities
 * are correctly included in TypeScript code generation prompts with their
 * substituted intent and expect content.
 */
class TsPromptBuilderTest {

    @Test
    void annotationsInForceJoinTheTsBodyPrompt() {
        Ast.Module m = Parsing.parse("""
                module shop
                annotation fast(level Int) { intent "Prefer O({level}) memory." }
                annotation stored(store Text) { intent "Persist through {store}." }
                @stored("mongodb")
                entity Product { id Int }
                @fast(1)
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                """, "shop.sky");
        new TypeChecker().check(m);
        Ast.Service catalog = m.services().get(0);
        String prompt = new TsPromptBuilder().user(m, catalog, catalog.methods().get(0), List.of());
        assertTrue(prompt.contains("@fast(1): Prefer O(1) memory."), prompt);
        assertTrue(prompt.contains("@stored(\"mongodb\"): Persist through mongodb."), prompt);
    }
}
