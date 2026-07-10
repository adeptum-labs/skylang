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
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Pins the exact spec text that freezes methods and views. Every frozen body in every existing
 * sky.lock is keyed by the hash of this text, so any change to it silently invalidates users'
 * locks and re-triggers synthesis for specs that did not change. If this test fails, either
 * restore the format or accept a deliberate, documented lock-format break.
 */
class FreezeFormatTest {

    private static final String SHOP = """
            module shop

            entity Product {
              id    Int
              name  Text
              stock Int @min(0)
            }

            service Catalog {

              all() -> [Product]
                intent "List every product in the catalog."

              restock(p Product, units Int) -> Product
                intent  "Return a copy of the product with its stock increased by units."
                requires units > 0
                ensures  result.stock == p.stock + units
                example  restock(Product(1, "Notebook", 5), 3) -> a Product with stock 8
            }

            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row, ask Int)
              expect table has columns (name, stock)
              expect action "Restock" is button
            }
            """;

    private static final String ENTITY_LINE = "entity Entity[name=Product, fields=[Field[name=id, "
            + "type=TypeRef[name=Int, list=false], id=false, min=OptionalLong.empty], Field[name=name, "
            + "type=TypeRef[name=Text, list=false], id=false, min=OptionalLong.empty], Field[name=stock, "
            + "type=TypeRef[name=Int, list=false], id=false, min=OptionalLong[0]]]]";

    private static final String RESTOCK_LINE = "Method[name=restock, params=[Param[name=p, "
            + "type=TypeRef[name=Product, list=false]], Param[name=units, type=TypeRef[name=Int, list=false]]], "
            + "returnType=TypeRef[name=Product, list=false], "
            + "intent=Optional[Return a copy of the product with its stock increased by units.], "
            + "requires=[BinExpr[op=>, left=NameExpr[name=units], right=IntLit[value=0]]], "
            + "ensures=[BinExpr[op===, left=MemberExpr[target=NameExpr[name=result], field=stock], "
            + "right=BinExpr[op=+, left=MemberExpr[target=NameExpr[name=p], field=stock], "
            + "right=NameExpr[name=units]]]], examples=[Example[call=CallExpr[callee=restock, "
            + "args=[CallExpr[callee=Product, args=[IntLit[value=1], StrLit[value=Notebook], IntLit[value=5]]], "
            + "IntLit[value=3]]], result=EntityResult[typeName=Product, fields=[FieldExpect[field=stock, "
            + "expected=IntLit[value=8]]]]]]]";

    private static final String GOLDEN_METHOD_SPEC = "profile=jvm-jakarta@0.1.0\n"
            + ENTITY_LINE + "\n"
            + "method " + RESTOCK_LINE + "\n";

    private static final String GOLDEN_VIEW_SPEC = "profile=jvm-jakarta@0.1.0\n"
            + ENTITY_LINE + "\n"
            + "service Service[name=Catalog, methods=[Method[name=all, params=[], "
            + "returnType=TypeRef[name=Product, list=true], "
            + "intent=Optional[List every product in the catalog.], requires=[], ensures=[], examples=[]], "
            + RESTOCK_LINE + "]]\n"
            + "view View[name=ProductList, route=Optional[/products], "
            + "shows=Shows[query=QualifiedCall[service=Catalog, method=all, args=[]], "
            + "projection=Optional[Projection[kind=table, columns=[name, stock]]]], "
            + "actions=[Action[label=Restock, rowVar=row, service=Catalog, method=restock, "
            + "args=[ExprArg[value=NameExpr[name=row]], AskArg[type=TypeRef[name=Int, list=false]]]]], "
            + "expects=[ExpectColumns[subject=table, columns=[name, stock]], "
            + "ExpectActionKind[label=Restock, kind=button]], appears=[]]\n";

    @Test
    void methodSpecStringIsStable() {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        Ast.Method restock = module.services().get(0).methods().get(1);
        assertEquals(GOLDEN_METHOD_SPEC, Pipeline.specString(module, restock));
    }

    @Test
    void viewSpecStringIsStable() {
        Ast.Module module = Parsing.parse(SHOP, "shop.sky");
        assertEquals(GOLDEN_VIEW_SPEC, Pipeline.viewSpecString(module, module.views().get(0)));
    }
}
