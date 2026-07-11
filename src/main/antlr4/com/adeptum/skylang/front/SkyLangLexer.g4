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

lexer grammar SkyLangLexer;

/*
 * Split from the parser grammar because regex literals need a lexer mode:
 * a bare '/.../' token would collide with division and '//' comments, so the
 * 'matching' keyword switches the lexer into RE mode for exactly one regex.
 *
 * Soft keywords ('a', 'with', 'and', 'now', and annotation names 'id'/'min'/'unique')
 * are matched as plain IDs and interpreted in AstBuilder, so they never shadow
 * identifiers (e.g. a parameter may legitimately be named 'a').
 */

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
TYPE     : 'type' ;
WHERE    : 'where' ;
MATCHING : 'matching' -> pushMode(RE) ;
TRUE     : 'true' ;
FALSE    : 'false' ;
USES     : 'uses' ;
VALUES   : 'values' ;
RAISES   : 'raises' ;
WHEN     : 'when' ;
OLD      : 'old' ;
NOT      : 'not' ;
FOR      : 'for' ;
EVERY    : 'every' ;
PAGE     : 'page' ;
FLOW     : 'flow' ;
STEP     : 'step' ;
COMPONENT : 'component' ;
PROMPT   : 'prompt' ;
TITLED   : 'titled' ;
POSS     : '\'s' ;
SPEC     : 'spec' ;
GIVEN    : 'given' ;
THEN     : 'then' ;
POLICY   : 'policy' ;
WHENEVER : 'whenever' ;
REQUIRE  : 'require' ;
FORBID   : 'forbid' ;
ELSE     : 'else' ;
RAISE    : 'raise' ;
CONTAINS : 'contains' ;

ARROW  : '->' ;
AT     : '@' ;
LBRACE : '{' ;
RBRACE : '}' ;
LPAREN : '(' ;
RPAREN : ')' ;
LBRACK : '[' ;
RBRACK : ']' ;
COMMA  : ',' ;
DOTDOT : '..' ;
DOT    : '.' ;

EQ  : '==' ;
NEQ : '!=' ;
LE  : '<=' ;
GE  : '>=' ;
LT  : '<' ;
GT  : '>' ;
ASSIGN : '=' ;
PLUS  : '+' ;
MINUS : '-' ;
STAR  : '*' ;
SLASH : '/' ;
AND : 'and' ;
OR  : 'or' ;

// A native block: a profile's keyword plus a balanced-brace body in that profile's
// language, captured as one token. (A brace inside a string literal would unbalance
// the count — keep such literals out of native blocks.)
NATIVE_BLOCK : ('java' | 'ts') [ \t\r\n]* BRACE_BLOCK ;
fragment BRACE_BLOCK : '{' ( ~[{}] | BRACE_BLOCK )* '}' ;

// A money literal carries a three-letter currency suffix, so '1..10' still
// lexes as INT DOTDOT INT (no letters follow the digits).
MONEY  : [0-9]+ ('.' [0-9]+)? [a-zA-Z] [a-zA-Z] [a-zA-Z] ;
INT    : [0-9]+ ;
STRING : '"' ( ~["\\] | '\\' . )* '"' ;
ID     : [a-zA-Z_] [a-zA-Z0-9_]* ;

WS            : [ \t\r\n]+ -> skip ;
LINE_COMMENT  : '//' ~[\r\n]* -> skip ;
BLOCK_COMMENT : '/*' .*? '*/' -> skip ;

mode RE;

RE_WS : [ \t]+ -> skip ;
REGEX : '/' ( ~[/\\\r\n] | '\\' . )* '/' -> popMode ;
