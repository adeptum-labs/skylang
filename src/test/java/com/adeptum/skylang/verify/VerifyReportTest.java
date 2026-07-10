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

package com.adeptum.skylang.verify;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The toolchain output is the only witness of a failed candidate; this parser turns it back
 * into (service, method, clause) so the transcript and the error report can speak SkyLang.
 */
class VerifyReportTest {

    private static final String SUREFIRE = """
            [INFO] Running shop.CatalogTest
            [ERROR] Tests run: 3, Failures: 2, Errors: 1, Skipped: 0
            [ERROR] Failures:\s
            [ERROR]   CatalogTest.restock_example_1:23 ensures: result.stock == p.stock + units [p=Product[id=1], units=3] ==> expected: <true> but was: <false>
            [ERROR]   CatalogTest.restock_example_1:23 ensures: result.stock == p.stock + units [p=Product[id=1], units=3] ==> expected: <true> but was: <false>
            [ERROR]   OrdersTest.place_spec_1:40 spec: an empty order is rejected ==> expected: <true> but was: <false>
            [ERROR] Errors:\s
            [ERROR]   CatalogTest.restock_raises_NotFound_1:31 raises NotFound when no product has that id ==> Expected shop.NotFound to be thrown, but nothing was thrown.
            """;

    @Test
    void attributesEachFailureToItsServiceMethodAndClause() {
        List<VerifyReport.ClauseFailure> failures = VerifyReport.clauseFailures(SUREFIRE);

        assertEquals(3, failures.size(), "repeated lines must collapse to one failure: " + failures);
        assertEquals(new VerifyReport.ClauseFailure("Catalog", "restock",
                "ensures: result.stock == p.stock + units"), failures.get(0));
        assertEquals(new VerifyReport.ClauseFailure("Orders", "place",
                "spec: an empty order is rejected"), failures.get(1));
        assertEquals(new VerifyReport.ClauseFailure("Catalog", "restock",
                "raises NotFound when no product has that id"), failures.get(2));
    }

    @Test
    void noFailuresParseFromACompileError() {
        String output = """
                [INFO] BUILD FAILURE
                [ERROR] COMPILATION ERROR :\s
                [ERROR] /tmp/build/jvm-jakarta/src/main/java/shop/Crypto.java:[14,9] cannot find symbol
                  symbol: class MessageDigets
                """;
        assertTrue(VerifyReport.clauseFailures(output).isEmpty());
        assertTrue(VerifyReport.compilationFailed(output));
        assertEquals(List.of("/tmp/build/jvm-jakarta/src/main/java/shop/Crypto.java:[14,9] cannot find symbol"),
                VerifyReport.compileErrors(output));
    }

    @Test
    void aTestFailureIsNotACompilationFailure() {
        assertFalse(VerifyReport.compilationFailed(SUREFIRE));
    }
}
