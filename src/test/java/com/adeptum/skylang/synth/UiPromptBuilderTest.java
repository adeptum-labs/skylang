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

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiPromptBuilderTest {

    private static final String SRC = """
            module shop
            entity Product { id Int  name Text  stock Int @min(0) }
            service Catalog {
              all() -> [Product]  intent "all"
              restock(id Int, units Int) -> Product  intent "restock"
            }
            view ProductList at "/products" {
              shows  Catalog.all() as a table of (name, stock)
              action "Restock" on row -> Catalog.restock(row.id, ask Int)
              expect table has columns (name, stock)
              appears action "Restock" in toolbar
              appears rows is compact
            }
            """;

    private final UiPromptBuilder prompts = new UiPromptBuilder();

    @Test
    void extractsMarkupAndBeanFromFencedReply() {
        String reply = """
                Here you go:
                ```xhtml
                <h:dataTable id="products" value="#{bean.rows}" var="row"/>
                ```
                and the bean:
                ```java
                public class ProductListBean {}
                ```
                """;
        UiPromptBuilder.UiArtifact art = prompts.extractArtifacts(reply);
        assertTrue(art.markup().contains("h:dataTable"));
        assertTrue(art.bean().contains("class ProductListBean"));
    }

    @Test
    void rejectsReplyMissingABlock() {
        String reply = "```xhtml\n<h:form/>\n```";   // no java block
        assertThrows(IllegalArgumentException.class, () -> prompts.extractArtifacts(reply));
    }

    @Test
    void systemPromptListsTheComponentVocabulary() {
        String system = prompts.system(List.of("h:dataTable", "p:dataTable"));
        assertTrue(system.contains("h:dataTable"));
        assertTrue(system.contains("p:dataTable"));
    }

    @Test
    void userPromptDescribesTheView() {
        Ast.Module m = Parsing.parse(SRC, "shop.sky");
        Ast.View view = m.views().get(0);
        String user = prompts.user(m, view);
        assertTrue(user.contains("ProductList"));
        assertTrue(user.contains("name"));
        assertTrue(user.contains("stock"));
        assertTrue(user.contains("Restock"));
    }

    private static final String BRANDING_SRC = """
            module shop
            entity Tenant { id Int @id  name Text  tagline Text  logo Maybe<Bytes> }
            service Tenants {
              current() -> Maybe<Tenant>  intent "The tenant for this request."
            }
            page Login at "/" {
              shows  Tenants.current() as a summary of (name, tagline, logo)
            }
            """;

    @Test
    void userPromptListsRequestParamsAndAppearsWhen() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Account { id Int @id  email Text }
                service Session {
                  current() -> Maybe<Account>  intent "x"
                }
                page Login at "/" {
                  param   accessDenied Bool
                  shows   Session.current() as a summary of (email)
                  appears the access-denied alert when accessDenied
                }
                """, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("Request params:") && user.contains("accessDenied Bool"),
                "declared params must reach the model:\n" + user);
        assertTrue(user.contains("styleClass=\"accessDenied\"")
                        && user.contains("rendered=\"#{bean.accessDenied}\""),
                "the conditional element contract must reach the model:\n" + user);
    }

    @Test
    void systemPromptStatesTheImageContract() {
        String system = prompts.system(UiPromptBuilder.STANDARD);
        assertTrue(system.contains("h:graphicImage"), system);
        assertTrue(system.contains("DataUri"), system);
    }

    @Test
    void userPromptFlagsBytesColumnsAsImages() {
        Ast.Module m = Parsing.parse(BRANDING_SRC, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("logo") && user.contains("h:graphicImage")
                        && user.contains("logoDataUri"),
                "a Bytes column must be flagged as an image with its bean helper:\n" + user);
    }

    @Test
    void dateAndDateTimeAsksNameTheirConverters() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Course { id Int  name Text }
                service Courses {
                  all() -> [Course]  intent "all"
                  schedule(id Int, starts Date, opensAt DateTime) -> Course  intent "schedule"
                }
                view Schedule at "/schedule" {
                  shows  Courses.all() as a table of (id)
                  action "Schedule" on row -> Courses.schedule(row.id, ask Date, ask DateTime)
                }
                """, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("<f:converter converterId=\"sky.date\"/>"),
                "an ask Date input names its converter:\n" + user);
        assertTrue(user.contains("<f:converter converterId=\"sky.datetime\"/>"),
                "an ask DateTime input names its converter:\n" + user);
    }

    @Test
    void userPromptSaysOnThePageForSubjectlessActions() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Account { id Int @id  email Text }
                service Session {
                  current() -> [Account]  intent "Accounts."
                  signOut() -> Account    intent "Sign out."
                }
                page Login at "/" {
                  shows  Session.current() as a table of (email)
                  action "Sign out" -> Session.signOut()
                }
                """, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("\"Sign out\" on the page -> Session.signOut()"), user);
    }

    @Test
    void userPromptKeepsOnARowForSubjectfulActions() {
        Ast.Module m = Parsing.parse(SRC, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("\"Restock\" on a row -> Catalog.restock("), user);
    }

    @Test
    void userPromptPrescribesANavigationButton() {
        Ast.Module m = Parsing.parse("""
                module shop
                entity Product { id Int  name Text  stock Int @min(0) }
                service Catalog {
                  all() -> [Product]  intent "all"
                }
                view ProductList at "/products" {
                  shows Catalog.all() as a table of (name, stock)
                }
                view Home at "/" {
                  shows Catalog.all() as a table of (name)
                  action "Products" -> page ProductList
                }
                """, "shop.sky");
        String user = prompts.user(m, m.views().get(1));
        assertTrue(user.contains("\"Products\" on the page -> navigate to page ProductList"), user);
        assertTrue(user.contains("<h:button outcome=\"ProductList\""), user);
    }

    @Test
    void userPromptDescribesAppearance() {
        Ast.Module m = Parsing.parse(SRC, "shop.sky");
        Ast.View view = m.views().get(0);
        String user = prompts.user(m, view);
        assertTrue(user.contains("toolbar"));
        assertTrue(user.contains("compact"));
    }

    @Test
    void systemPromptStatesTheRenderConvention() {
        String system = prompts.system(UiPromptBuilder.STANDARD);
        assertTrue(system.contains("styleClass"));
    }

    @Test
    void userPromptCarriesTheCalledServiceSignatures() {
        Ast.Module m = Parsing.parse(SRC, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("Services"), user);
        assertTrue(user.contains("Catalog.all()"), "the query signature is listed:\n" + user);
        assertTrue(user.contains("Catalog.restock(id Int, units Int) -> Product"),
                "the action's exact signature is listed so the bean matches it:\n" + user);
    }

    @Test
    void systemPromptPinsTheBeanContract() {
        String system = prompts.system(UiPromptBuilder.STANDARD);
        assertTrue(system.contains("jakarta.faces.view"),
                "the correct ViewScoped import is pinned:\n" + system);
        assertTrue(system.contains("Bean"), "the <View>Bean class-name convention is stated");
        assertTrue(system.toLowerCase().contains("inject"), "services are injected, not called statically");
        assertTrue(system.contains("Optional"), "a Maybe return maps to Optional");
    }

    // ----- the chapter-3 type surface -----------------------------------------

    private static final String PAY_SRC = """
            module shop
            type Quantity = Int(1..)
            entity Order { id Int  total Money }
            service Orders {
              all() -> [Order]  intent "all"
              pay(id Int, amount Money, units Quantity, contact Email, moment Instant) -> Order  intent "pay"
            }
            view Pay at "/pay" {
              shows  Orders.all() as a table of (id)
              action "Pay" on row -> Orders.pay(row.id, ask Money, ask Quantity, ask Email, ask Instant)
            }
            """;

    @Test
    void userPromptDerivesConvertersAndValidatorsFromAskTypes() {
        Ast.Module m = Parsing.parse(PAY_SRC, "shop.sky");
        String user = prompts.user(m, m.views().get(0));
        assertTrue(user.contains("sky.money"), "a Money input needs the staged Money converter");
        assertTrue(user.contains("sky.instant"), "an Instant input needs the staged Instant converter");
        assertTrue(user.contains("f:validateLongRange") && user.contains("minimum=\"1\""),
                "a ranged Int input derives its range validator");
        assertTrue(user.contains("f:validateRegex"), "an Email input derives its shape validator");
    }

    @Test
    void systemPromptCarriesConverterAndSecretRules() {
        String system = prompts.system(UiPromptBuilder.STANDARD);
        assertTrue(system.contains("f:converter"), "the converter tag must be in the vocabulary rules");
        assertTrue(system.contains("Secret"), "the Secret exclusion rule must be stated");
    }

    @Test
    void standardVocabularyCoversConvertersAndValidators() {
        assertTrue(UiPromptBuilder.STANDARD.contains("f:converter"));
        assertTrue(UiPromptBuilder.STANDARD.contains("f:validateLongRange"));
        assertTrue(UiPromptBuilder.STANDARD.contains("f:validateLength"));
        assertTrue(UiPromptBuilder.STANDARD.contains("f:validateRegex"));
    }
}
