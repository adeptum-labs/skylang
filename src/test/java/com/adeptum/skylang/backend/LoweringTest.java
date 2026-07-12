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
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoweringTest {

    private static Ast.Type type(String skyType) {
        Ast.Module m = Parsing.parse("""
                module t
                type Slug     = Text matching /^[a-z0-9-]{1,64}$/
                type Quantity = Int(1..)
                entity Product { id Int }
                entity Box { x %s }
                """.formatted(skyType), "t.sky");
        return m.entities().get(1).fields().get(0).type();
    }

    private static Map<String, Ast.TypeDecl> types() {
        Ast.Module m = Parsing.parse("""
                module t
                type Slug     = Text matching /^[a-z0-9-]{1,64}$/
                type Quantity = Int(1..)
                """, "t.sky");
        return Lowering.typesOf(m);
    }

    @Test
    void lowersPrimitiveAndSpecialTypes() {
        Map<String, Ast.TypeDecl> none = Map.of();
        assertEquals("long", Lowering.javaType(type("Int"), none));
        assertEquals("String", Lowering.javaType(type("Text"), none));
        assertEquals("boolean", Lowering.javaType(type("Bool"), none));
        assertEquals("Money", Lowering.javaType(type("Money"), none));
        assertEquals("java.time.Instant", Lowering.javaType(type("Instant"), none));
        assertEquals("Bytes", Lowering.javaType(type("Bytes"), none));
        assertEquals("String", Lowering.javaType(type("Email"), none));
    }

    @Test
    void lowersRefinedTypesByErasure() {
        assertEquals("long", Lowering.javaType(type("Int(0..100)"), Map.of()));
        assertEquals("String", Lowering.javaType(type("Text(1..120)"), Map.of()));
        assertEquals("String", Lowering.javaType(type("Slug"), types()));
        assertEquals("long", Lowering.javaType(type("Quantity"), types()));
    }

    @Test
    void lowersContainersWithBoxedArguments() {
        Map<String, Ast.TypeDecl> none = Map.of();
        assertEquals("java.util.List<String>", Lowering.javaType(type("[Text]"), none));
        assertEquals("java.util.List<Product>", Lowering.javaType(type("List<Product>"), none));
        assertEquals("java.util.Set<Long>", Lowering.javaType(type("Set<Int>"), none));
        assertEquals("java.util.Map<String, Money>", Lowering.javaType(type("Map<Text, Money>"), none));
        assertEquals("java.util.Optional<Product>", Lowering.javaType(type("Maybe<Product>"), none));
        assertEquals("java.util.Optional<Long>", Lowering.javaType(type("Maybe<Int>"), none));
        assertEquals("Secret<Bytes>", Lowering.javaType(type("Secret<Bytes>"), none));
        assertEquals("java.util.List<java.util.Optional<Long>>", Lowering.javaType(type("[Maybe<Int>]"), none));
    }

    @Test
    void lowersMoneyAndBoolLiterals() {
        assertEquals("Money.of(\"9.99\", \"EUR\")",
                Lowering.javaValue(new Ast.MoneyLit(new java.math.BigDecimal("9.99"), "EUR")));
        assertEquals("true", Lowering.javaValue(new Ast.BoolLit(true)));
    }

    @Test
    void lowersOperatorsThroughOverloadedHelpers() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Product { id Int  stock Int }
                service S {
                  f(p Product, units Int) -> Product
                    intent  "x"
                    ensures result.stock == p.stock + units
                }
                """, "t.sky");
        Ast.Expr ensures = m.services().get(0).methods().get(0).ensures().get(0);
        assertEquals("eq((result).stock(), plus((p).stock(), units))",
                Lowering.exprToJava(ensures, Map.of()));
    }

    @Test
    void lowersValueSetConstants() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Role { name Text @id  values Member, Admin }
                entity User { id Int @id  role Role }
                service S {
                  f(u User) -> Bool
                    intent  "x"
                    ensures u.role == Role.Admin
                }
                """, "t.sky");
        Ast.Expr ensures = m.services().get(0).methods().get(0).ensures().get(0);
        assertEquals("eq((u).role(), Role.Admin)",
                Lowering.exprToJava(ensures, Map.of(), java.util.Set.of("Role")));
    }

    @Test
    void scopedUniqueWitnessColumnsStayDistinctPerWitness() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Provider { id Int @id  name Text }
                entity UserAccount {
                  id       Int @id
                  provider Provider
                  email    Email @unique(provider)
                }
                """, "t.sky");
        String w1 = Lowering.entityWitness("UserAccount", Map.of(), 1, m, Map.of(), java.util.Set.of());
        String w2 = Lowering.entityWitness("UserAccount", Map.of(), 2, m, Map.of(), java.util.Set.of());
        assertTrue(w1.contains("\"w1@example.com\"") && w2.contains("\"w2@example.com\""),
                "a scoped column keeps per-witness distinct values; column-distinct implies"
                        + " tuple-distinct: " + w1 + " / " + w2);
    }

    @Test
    void lowersLengthThroughTheDispatchingHelper() {
        Ast.Module m = Parsing.parse("""
                module t
                service S {
                  keep(input [Text]) -> [Text]
                    intent  "x"
                    ensures result.length == input.length
                }
                """, "t.sky");
        Ast.Expr ensures = m.services().get(0).methods().get(0).ensures().get(0);
        assertEquals("eq(len(result), len(input))", Lowering.exprToJava(ensures, Map.of()));
    }

    @Test
    void lowersFixtureWitnesses() {
        Ast.Module m = Parsing.parse("""
                module t
                entity Wallet { id Int @id  owner Email @unique  balance Money }
                service S uses db {
                  withdraw(w Wallet, amount Money) -> Wallet
                    intent  "x"
                    example withdraw(wallet_with(100eur), 30eur) -> balance 70eur
                }
                """, "t.sky");
        Ast.Expr fixture = m.services().get(0).methods().get(0).examples().get(0).call().args().get(0);
        String lowered = Lowering.exprToJava(fixture, Map.of(), m);
        assertTrue(lowered.startsWith("new Wallet("), lowered);
        assertTrue(lowered.contains("Money.of(\"100\", \"EUR\")"), lowered);
        assertTrue(lowered.contains("@example.com"), lowered);
    }

    @Test
    void lowersComparisonsAndLogicThroughHelpers() {
        Ast.Module m = Parsing.parse("""
                module t
                service S {
                  f(a Int, b Int) -> Bool
                    intent  "x"
                    requires a < b and a != 0
                }
                """, "t.sky");
        Ast.Expr requires = m.services().get(0).methods().get(0).requires().get(0);
        assertEquals("(lt(a, b) && !eq(a, 0L))", Lowering.exprToJava(requires, Map.of()));
    }
}
