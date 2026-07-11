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

import com.adeptum.skylang.backend.ProjectStager;
import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The sky test summary counts the tests actually staged per service — the same suite that
 * gates synthesis — and names the policies that held over every body.
 */
class TestCommandTest {

    @Test
    void theSummaryCountsStagedTestsAndNamesPolicies(@TempDir Path root) {
        Ast.Module module = Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                policy NoSecretsInLogs { whenever a Secret is passed to a logger forbid }
                service Catalog {
                  restock(p Product, units Int) -> Product
                    intent  "Increase stock."
                    requires units > 0
                    ensures  result.stock == p.stock + units
                    example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
                }
                """, "shop.sky");
        new TypeChecker().check(module);
        Path buildDir = root.resolve("build/jvm-jakarta");
        new ProjectStager().stage(module,
                Map.of("shop.Catalog.restock", "return new Product(p.id(), p.name(), p.stock() + units);"),
                buildDir);

        String summary = TestCommand.summary(module, "jvm-jakarta", buildDir);

        assertTrue(summary.matches("(?s).*Catalog:\\s+\\d+ tests {2}✓.*"), summary);
        assertTrue(summary.contains("policies: NoSecretsInLogs ✓ (1 bodies)"), summary);
        assertTrue(summary.endsWith("all green.\n"), summary);
    }
}
