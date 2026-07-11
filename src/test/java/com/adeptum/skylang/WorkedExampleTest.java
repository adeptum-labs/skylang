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
import com.adeptum.skylang.types.TypeChecker;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The book's worked shop is the living parity fixture: the whole specification — domain,
 * behaviour, and interface — must parse and type-check exactly as the checkpoints show.
 */
class WorkedExampleTest {

    @Test
    void theWorkedShopChecksEndToEnd() throws Exception {
        Ast.Module module = Parsing.parseFile(Path.of("examples/worked/shop.sky"));
        new TypeChecker().check(module);

        int methods = module.services().stream().mapToInt(s -> s.methods().size()).sum();
        assertEquals(19, methods, "the interface chapter's checkpoint counts nineteen methods");
        assertEquals(4, module.views().size(), "four pages");
        assertEquals(1, module.flows().size(), "one flow");
        assertEquals(1, module.components().size(), "one component");
        assertEquals(2, module.policies().size(), "two policies");

        Ast.Flow checkout = module.flows().get(0);
        assertEquals(3, checkout.steps().size(), checkout.toString());
        assertEquals(2, checkout.transitions().size(), checkout.toString());
        assertEquals(2, checkout.expects().size(), checkout.toString());

        Ast.Component badge = module.components().get(0);
        assertEquals("StockBadge", badge.name());
        assertEquals(2, badge.appears().size(), badge.toString());

        Ast.View productList = module.views().get(0);
        assertTrue(productList.shows().projection().orElseThrow().sortable(),
                "the product table is declared sortable");
        Ast.View dashboard = module.views().stream()
                .filter(v -> v.name().equals("Dashboard")).findFirst().orElseThrow();
        assertEquals(2, dashboard.moreShows().size(), "the dashboard composes three sources");
        assertEquals("Low stock", dashboard.shows().title().orElseThrow());
    }
}
