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

grammar SkyLang;

/*
 * Thin-slice SkyLang grammar. Package is derived from the folder path by the
 * antlr4-maven-plugin (com/adeptum/skylang/front), so no @header is needed.
 *
 * Soft keywords ('a', 'with', 'and', and annotation names 'id'/'min') are matched
 * as plain IDs and interpreted in AstBuilder, so they never shadow identifiers
 * (e.g. a parameter may legitimately be named 'a').
 */

module_ : MODULE ID decl* EOF ;

decl : entity | service | view ;

// ----- entities -------------------------------------------------------------

entity : ENTITY ID LBRACE field* RBRACE ;

field : ID type annotation* ;

annotation : AT ID (LPAREN INT RPAREN)? ;   // @id  |  @min(0)

// ----- services & methods ---------------------------------------------------

service : SERVICE ID LBRACE method* RBRACE ;

method : ID LPAREN params? RPAREN ARROW type clause+ ;

params : param (COMMA param)* ;
param  : ID type ;

type
    : ID               # namedType    // Int | Text | an entity name
    | LBRACK ID RBRACK # listType      // [Product] — a list of an entity
    ;

clause
    : INTENT STRING                        # intentClause
    | REQUIRES expr                        # requiresClause
    | ENSURES expr                         # ensuresClause
    | EXAMPLE call ARROW exampleResult     # exampleClause
    ;

exampleResult
    : ID ID withClause?    # entityResult   // "a" TypeName ["with" ...]
    | expr                 # exprResult     // e.g. -> 5
    ;

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
    | ID LPAREN args? RPAREN                     # callExpr      // f(...) or Ctor(...)
    | expr op=(STAR | SLASH) expr               # mulExpr
    | expr op=(PLUS | MINUS) expr               # addExpr
    | expr op=(EQ | NEQ | LT | LE | GT | GE) expr   # cmpExpr
    | expr op=(AND | OR) expr                    # logicExpr
    | INT                                        # intLit
    | STRING                                     # strLit
    | ID                                         # nameExpr
    ;

// ----- lexer ----------------------------------------------------------------

MODULE   : 'module' ;
ENTITY   : 'entity' ;
SERVICE  : 'service' ;
INTENT   : 'intent' ;
REQUIRES : 'requires' ;
ENSURES  : 'ensures' ;
EXAMPLE  : 'example' ;
VIEW     : 'view' ;
SHOWS    : 'shows' ;
ACTION   : 'action' ;
EXPECT   : 'expect' ;
AT_KW    : 'at' ;
AS       : 'as' ;
OF       : 'of' ;
ON       : 'on' ;
HAS      : 'has' ;
COLUMNS  : 'columns' ;
ASK      : 'ask' ;
IS       : 'is' ;
APPEARS  : 'appears' ;
IN       : 'in' ;

ARROW  : '->' ;
AT     : '@' ;
LBRACE : '{' ;
RBRACE : '}' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACK : '[' ;
RBRACK : ']' ;
COMMA  : ',' ;
DOT    : '.' ;

EQ  : '==' ;
NEQ : '!=' ;
LE  : '<=' ;
GE  : '>=' ;
LT  : '<' ;
GT  : '>' ;
PLUS  : '+' ;
MINUS : '-' ;
STAR  : '*' ;
SLASH : '/' ;
AND : 'and' ;
OR  : 'or' ;

INT    : [0-9]+ ;
STRING : '"' ( ~["\\] | '\\' . )* '"' ;
ID     : [a-zA-Z_] [a-zA-Z0-9_]* ;

WS           : [ \t\r\n]+ -> skip ;
LINE_COMMENT : '//' ~[\r\n]* -> skip ;
