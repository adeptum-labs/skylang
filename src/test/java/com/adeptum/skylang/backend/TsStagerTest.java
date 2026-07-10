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

package com.adeptum.skylang.backend;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The ts-node backend stages a conventional Node project: TypeScript sources with the frozen
 * bodies spliced in, the contracts as node:test tests, and a tsconfig the platform's own
 * toolchain compiles. Same meanings as the JVM staging, different representations.
 */
class TsStagerTest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog uses db {
              restock(p Product, units Int) -> Product
                intent  "Increase stock."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }
            """;

    private static final String BODY =
            "const updated = new Product(p.id, p.name, p.stock + units);\nreturn this.db.save(updated);";

    private Path stage(Path root) throws IOException {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(module);
        new TsStager().stage(module, Map.of("shop.Catalog.restock", BODY), root);
        return root;
    }

    @Test
    void stagesAnEntityAsAValidatedClass(@TempDir Path root) throws IOException {
        String product = Files.readString(stage(root).resolve("src/Product.ts"));
        assertTrue(product.contains("export class Product"), product);
        assertTrue(product.contains("readonly id: bigint"), product);
        assertTrue(product.contains("readonly name: string"), product);
        assertTrue(product.contains("readonly stock: bigint"), product);
        assertTrue(product.contains("if (this.stock < 0n) throw new RangeError"),
                "@min lowers to a constructor guard:\n" + product);
        assertTrue(product.contains("static readonly idField = \"id\""),
                "the store needs to know the identity field:\n" + product);
    }

    @Test
    void splicesTheFrozenBodyIntoTheService(@TempDir Path root) throws IOException {
        String catalog = Files.readString(stage(root).resolve("src/Catalog.ts"));
        assertTrue(catalog.contains("export class Catalog"), catalog);
        assertTrue(catalog.contains("constructor(private readonly db: Db)"), catalog);
        assertTrue(catalog.contains("restock(p: Product, units: bigint): Product {"), catalog);
        assertTrue(catalog.contains("return this.db.save(updated);"),
                "the frozen body is spliced verbatim:\n" + catalog);
    }

    @Test
    void materialisesTheContractsAsNodeTests(@TempDir Path root) throws IOException {
        String tests = Files.readString(stage(root).resolve("src/Catalog.test.ts"));
        assertTrue(tests.contains("import { test } from \"node:test\""), tests);
        assertTrue(tests.contains("const p = new Product(1n, \"Notebook\", 5n);"), tests);
        assertTrue(tests.contains("const result = svc.restock(p, units);"), tests);
        assertTrue(tests.contains("ensures: result.stock == p.stock + units"),
                "the ensures clause is asserted with its text:\n" + tests);
        assertTrue(tests.contains("assert.equal(result.stock, 8n"),
                "the example's expected field is asserted:\n" + tests);
        assertTrue(tests.contains("assert.throws"), "requires gets a boundary test:\n" + tests);
        assertTrue(tests.contains("RangeError"),
                "a violated precondition surfaces as a RangeError:\n" + tests);
    }

    @Test
    void stagesAConventionalNodeProject(@TempDir Path root) throws IOException {
        Path dir = stage(root);
        String pkg = Files.readString(dir.resolve("package.json"));
        assertTrue(pkg.contains("\"type\": \"module\""), pkg);
        String tsconfig = Files.readString(dir.resolve("tsconfig.json"));
        assertTrue(tsconfig.contains("\"strict\": true"), tsconfig);
        assertTrue(tsconfig.contains("\"outDir\": \"dist\""), tsconfig);
        assertTrue(Files.exists(dir.resolve("src/Db.ts")), "the db effect stages its binding");
    }
}
