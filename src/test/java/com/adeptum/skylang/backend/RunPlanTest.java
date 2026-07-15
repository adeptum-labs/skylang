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
import com.adeptum.skylang.types.CheckException;
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A run plan is what {@code sky run} executes, so it is worth pinning down as a value: the profile
 * decides how its artifact is served, and it must serve the artifact the build emitted.
 */
class RunPlanTest {

    private static final String SHOP = """
            module shop
            entity Product { id Int  name Text }
            service Catalog {
              all() -> [Product]  intent "Every product."
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name)
            }
            """;

    private static Ast.Module checked(String source) {
        Ast.Module module = Parsing.parse(source, "shop.sky");
        new TypeChecker().check(module);
        return module;
    }

    @Test
    void servesThePackagedWarOnTheRequestedPort() {
        Path buildDir = Path.of("/tmp/build/jvm-jakarta");

        RunPlan plan = JvmProfile.INSTANCE.runPlan(checked(SHOP), "fikus", buildDir, 8080);

        assertEquals(buildDir.resolve("target/fikus.war"), plan.artifact(),
                "run must serve the artifact `sky build` emitted, not the staged tree");
        assertEquals(buildDir, plan.directory());
        assertEquals("/", plan.landingPath(), "the app's front door is its root");
        assertTrue(plan.command().contains("-Dexec.mainClass=shop.RunServer"), plan.command().toString());
        assertTrue(plan.command().contains("-Dexec.classpathScope=test"),
                "the container hosting the war comes from the test classpath: " + plan.command());
        assertTrue(plan.command().contains("-Dexec.args=8080 " + buildDir.resolve("target/fikus.war")),
                "the port and the artifact are handed to the server: " + plan.command());
    }

    @Test
    void aModuleWithNoViewsHasNothingToRun() {
        Ast.Module headless = checked("""
                module shop
                entity Product { id Int  name Text }
                service Catalog {
                  all() -> [Product]  intent "Every product."
                }
                """);

        CheckException e = assertThrows(CheckException.class,
                () -> JvmProfile.INSTANCE.runPlan(headless, "fikus", Path.of("/tmp/build"), 8080));
        assertTrue(e.getMessage().contains("no views to run"), e.getMessage());
    }

    @Test
    void aProfileWithoutTheInterfaceLibraryCannotRun() {
        Ast.Module module = checked(SHOP);

        CheckException e = assertThrows(CheckException.class,
                () -> TsProfile.INSTANCE.runPlan(module, "fikus", Path.of("/tmp/build"), 8080));
        assertTrue(e.getMessage().contains("not yet supported"), e.getMessage());
    }
}
