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

parser grammar SkyLangParser;

options { tokenVocab = SkyLangLexer; }

// Package is derived from the folder path by the antlr4-maven-plugin
// (com/adeptum/skylang/front), so no @header is needed.

module_ : MODULE ID decl* EOF ;

decl : entity | service | view | typeDecl | policy ;

// ----- policies: cross-cutting contracts -------------------------------------

policy : POLICY ID LBRACE WHENEVER wheneverPhrase policyRule RBRACE ;

// "a Password is constructed" | "a Secret is passed to a logger" — phrase words
// stay soft identifiers, validated in the builder.
wheneverPhrase : ID ID IS ID (ID ID ID)? ;

policyRule
    : REQUIRE requireTerm (AND requireTerm)* (ELSE RAISE ID)?  # requireRule
    | FORBID                                                    # forbidRule
    ;

requireTerm
    : CONTAINS ID ID   # containsTerm    // contains a symbol
    | expr             # exprTerm        // length >= 12
    ;

// ----- named refined types ---------------------------------------------------

typeDecl : TYPE ID ASSIGN refinedType ;

refinedType
    : ID LPAREN range RPAREN  # rangeRefinement   // type Percentage = Int(0..100)
    | ID MATCHING REGEX       # regexRefinement   // type Slug = Text matching /^[a-z]+$/
    | ID WHERE expr           # whereRefinement   // type PositiveMoney = Money where amount > 0
    ;

range : lo=INT? DOTDOT hi=INT? ;

// ----- entities -------------------------------------------------------------

entity : ENTITY ID LBRACE field* valuesClause? RBRACE ;

// values Member, Admin — seeds and closes an enum-like entity's instance set
valuesClause : VALUES ID (COMMA ID)* ;

field : ID type annotation* (ASSIGN expr)? ;

annotation : AT ID (LPAREN INT RPAREN)? ;   // @id  |  @unique  |  @min(0)

// ----- services & methods ---------------------------------------------------

service : SERVICE ID (USES ID (COMMA ID)*)? LBRACE method* RBRACE ;   // uses db, clock

method : ID LPAREN params? RPAREN ARROW type clause+ ;

params : param (COMMA param)* ;
param  : ID type ;

type
    : ID LT type (COMMA type)* GT # genericType   // Maybe<User> | Map<Slug, Product>
    | ID LPAREN range RPAREN      # rangedType    // Int(0..100) | Text(1..120)
    | ID                          # namedType     // Int | Text | an entity or declared type name
    | LBRACK type RBRACK          # listType      // [Product] — shorthand for List<Product>
    ;

clause
    : INTENT STRING                                # intentClause
    | REQUIRES expr                                # requiresClause
    | ENSURES expr+                                # ensuresClause   // continuation lines join one keyword
    | EXAMPLE call (ON seed)? ARROW exampleResult  # exampleClause
    | RAISES ID WHEN raisesCondition               # raisesClause
    | SPEC STRING LBRACE (GIVEN expr)? WHEN call THEN thenAssert+ RBRACE  # specClause
    | NATIVE_BLOCK                                 # nativeClause   // java { ... }
    ;

seed : ID ID withClause? ;   // on a Product with stock 5 — a stored row before the call

thenAssert
    : RAISES ID    # thenRaises
    | expr         # thenExpr
    ;

// The when-vocabulary: a formal condition, or one of the fixed domain phrases
// ("no product has that id", "email already registered") the checker resolves
// against the declared model. Phrase words stay soft and are validated in the builder.
raisesCondition
    : ID ID HAS ID ID                  # existenceCondition   // no product has that id
    | expr ID ID                       # phraseCondition      // email already registered
    | ID ID POSS ID IS ID (OR ID)*     # statusCondition      // the order's status is Shipped or Cancelled
    | expr                             # exprCondition        // units <= 0
    | proseWord+                       # proseCondition       // free prose: guides the model, checked by examples
    ;

proseWord : ID | HAS | IS | OR | AND | NOT | IN | OF | ON | INT | STRING | GT | LT | GE | LE | POSS ;

exampleResult
    : ID ID whosePart (AND whosePart)*                # whoseResult    // "a" User whose email is ...
    | ID ID withClause?                               # entityResult   // "a" TypeName ["with" ...]
    | RAISES ID                                       # raisesResult   // -> raises BadInput
    | fieldExpect ((ID | AND | COMMA) fieldExpect)*   # fieldsResult   // -> stock 8
    | expr                                            # exprResult     // e.g. -> 5 | -> nothing
    ;

whosePart : ID ID IS NOT? expr ;   // "whose" field is [not] value; the value "set" means present

withClause  : ID fieldExpect ((ID | AND | COMMA) fieldExpect)* ;  // "with" f v ("and"|",") ...
fieldExpect : ID expr ;                                     // "stock 8"  =>  field == value

call : ID LPAREN args? RPAREN ;
args : expr (COMMA expr)* ;

// ----- views ----------------------------------------------------------------

view : VIEW ID route? LBRACE viewClause* RBRACE ;

route : AT_KW STRING ;

viewClause
    : SHOWS viewQuery (AS projection)?          # showsClause
    | ACTION STRING ON ID ARROW actionTarget    # actionClause
    | EXPECT expectPred                         # expectClause
    | APPEARS appearsPred                       # appearsClause
    ;

viewQuery    : ID DOT ID LPAREN args? RPAREN ;                        // Catalog.all()
projection   : ID ID OF LPAREN ID (COMMA ID)* RPAREN ;               // a table of (name, stock)
actionTarget : ID DOT ID LPAREN actionArg (COMMA actionArg)* RPAREN ; // Catalog.restock(row.id, ask Int)
actionArg    : expr | ASK type ;                                     // row.id  |  ask Int

expectPred
    : ID HAS COLUMNS LPAREN ID (COMMA ID)* RPAREN  # expectColumns     // table has columns (name, stock)
    | ACTION STRING IS ID                          # expectActionKind  // action "Restock" is button
    ;

appearsPred
    : ACTION STRING IN ID                          # appearsPlacement    // action "Restock" in toolbar
    | ID IS ID                                     # appearsStyle        // rows is compact
    | COLUMNS LPAREN ID (COMMA ID)* RPAREN         # appearsColumnOrder  // columns (name, stock)
    ;

// ----- expressions (ANTLR left-recursion handles precedence) ----------------

expr
    : LPAREN expr RPAREN                        # parenExpr
    | expr DOT ID                               # memberExpr
    | OLD LPAREN expr RPAREN                    # oldExpr       // old(product.stock) — the pre-call value
    | ID OF LPAREN expr FOR ID IN aggSource (WHERE expr)? RPAREN # aggExpr  // sum/count of (...)
    | ID LPAREN args? RPAREN                     # callExpr      // f(...) or Ctor(...)
    | expr op=(STAR | SLASH) expr               # mulExpr
    | expr op=(PLUS | MINUS) expr               # addExpr
    | expr op=(EQ | NEQ | LT | LE | GT | GE) expr   # cmpExpr
    | expr IS NOT? expr                         # isExpr        // status is not Pending | tags is empty
    | NOT expr                                   # notExpr
    | expr op=(AND | OR) expr                    # logicExpr
    | EVERY ID IN expr HAS expr                  # forallExpr    // every product in result has f == v
    | MONEY                                      # moneyLit
    | INT                                        # intLit
    | STRING                                     # strLit
    | TRUE                                       # trueLit
    | FALSE                                      # falseLit
    | ID                                         # nameExpr
    ;

aggSource
    : ID ID    # allSource     // "all products" — the module's stored entities of that kind
    | expr     # exprSource
    ;
