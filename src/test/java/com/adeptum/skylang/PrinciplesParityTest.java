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

package com.adeptum.skylang;

import com.adeptum.skylang.front.Parsing;
import com.adeptum.skylang.front.ast.Ast;
import com.adeptum.skylang.synth.StubLlm;
import com.adeptum.skylang.types.TypeChecker;
import com.adeptum.skylang.verify.VerificationResult;
import com.adeptum.skylang.verify.Verifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The book's closing principles, each already enforced somewhere in the suite; this class adds the
 * one guarantee no other test pins directly — that the emitted artifact carries no model at all.
 *
 * <p>The eight principles map to their enforcement as follows: (1) types are the contract —
 * {@code TypeCheckerTest} (types win, refinements, nominal); (2) the compiler verifies even native
 * code — {@code PipelineTest.nativeVerifyFailuresDoNotRegenerate}; (3) the model lives at build
 * time, never at runtime — this test, plus {@code secondBuildReusesFrozenBodyWithoutCallingModel};
 * (4) determinism by freezing — {@code FreezeFormatTest} and the frozen-body reuse tests; (5) tests
 * can drive generation — the example/spec lowering tests and {@code TddCommand}; (6) always an
 * escape hatch — {@code PipelineTest.nativeBodiesBypassTheModelAndFreeze}; (7) no null —
 * {@code TypeCheckerTest.nothingNeedsAMaybeReturn} and {@code LandscapeParityTest}; (8) the core is
 * target-independent — {@code PipelineTest.theTsNodeProfileFreezesTypeScript} and the retarget test.
 */
class PrinciplesParityTest {

    private static final String SHOP = """
            module shop
            entity Product { id Int @id  name Text  stock Int @min(0) }
            service Catalog uses db {
              restock(id Int, units Int) -> Product
                intent  "Increase the stored product's stock by units."
                ensures result.stock == old(result.stock) + units
                example restock(1, 3) on a Product with stock 5 -> stock 8
            }
            """;

    /** Model-client library coordinates that must never be a runtime dependency. */
    private static final List<String> MODEL_LIBRARIES = List.of("langchain4j", "anthropic", "openai");

    /** In staged source, the model client and the compiler itself must be absent too. */
    private static final List<String> SOURCE_MARKERS =
            List.of("langchain4j", "anthropic", "openai", "com.adeptum.skylang");

    private static final Verifier ALWAYS_PASS = dir -> VerificationResult.pass();

    private static PrintStream quiet() {
        return new PrintStream(new ByteArrayOutputStream());
    }

    private static Ast.Module checked() {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    @Test
    void theEmittedArtifactDependsOnNoModel(@TempDir Path root) throws IOException {
        Path buildDir = root.resolve("build/jvm-jakarta");
        new Pipeline(new StubLlm("return null;"), ALWAYS_PASS)
                .build(checked(), root.resolve("sky.lock"), buildDir, quiet(), quiet());

        // The runtime dependency graph names no model library or provider.
        String pom = Files.readString(buildDir.resolve("pom.xml")).toLowerCase(Locale.ROOT);
        for (String marker : MODEL_LIBRARIES) {
            assertFalse(pom.contains(marker),
                    "the staged POM must not depend on '" + marker + "':\n" + pom);
        }

        // No staged main source reaches back into the compiler or the model client either.
        try (Stream<Path> sources = Files.walk(buildDir.resolve("src/main/java"))) {
            for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                String text = Files.readString(source).toLowerCase(Locale.ROOT);
                for (String marker : SOURCE_MARKERS) {
                    assertFalse(text.contains(marker),
                            source.getFileName() + " must not reference '" + marker + "'");
                }
            }
        }

        // What the model produced is present only as frozen source, not as a runtime call.
        assertTrue(Files.exists(buildDir.resolve("src/main/java/shop/Catalog.java")),
                "the generated body ships as ordinary staged source");
    }
}
