grammar Jetta;

program
    : statement* EOF
    ;

statement
    : run
    | expression
    ;

run
    : BANG expression
    ;

expression
    : LPAREN (atom)* RPAREN
    ;

atom
    : quoted
    | variable
    | symbol
    | number
    | string
    | special
    | expression
    ;

quoted
    : QUOTE atom
    ;

number
    : integer
    | long
    | double
    ;

string
    : STRING
    ;

integer
    : INTEGER
    ;

long
    : LONG
    ;

double
    : DOUBLE
    ;

special
    : pattern
    | package
    | annotation
    | type
    | arrow
    | plus
    | minus
    | times
    | if
    | eq
    | neq
    | lt
    | gt
    | le
    | ge
    | divide
    | lambda
    | seq
    ;

seq
    : SEQ
    ;

annotation
    : AT
    ;

lambda
    : LAMBDA
    ;

eq
    : EQ
    ;

neq
    : NEQ
    ;

lt
    : LT
    ;

gt
    : GT
    ;

le
    : LE
    ;

ge
    : GE
    ;

divide
    : DIVIDE
    ;

plus
    : PLUS
    ;

minus
    : MINUS
    ;

times
    : TIMES
    ;

pattern
    : EQUAL
    ;

type
    : COLON
    ;

arrow
    : ARROW
    ;

if
    : IF
    ;

package
    : PACKAGE
    ;

variable
    : DOLLAR identifier
    ;

identifier
    : IDENT
    | IDENT_WITH_ID
    ;

symbol
    : IDENT
    ;

STRING
    : '"' ( ~["\\] | '\\' . )* '"'
    ;

AT
    : '@'
    ;

BANG
    : '!'
    ;

QUOTE
    : '\''
    ;

WS
    : [ \t\r\n] -> skip
    ;

COMMENT
    : ';' .*? ( '\n' | EOF ) -> skip
    ;

fragment NON_NEGATIVE_INTEGER
    : ZERO
    | NON_ZERO_DIGIT DIGIT*
    ;

IDENT_WITH_ID
    : IDENT '#' NON_NEGATIVE_INTEGER
    ;

INTEGER
    : ZERO
    | ('-' | '+')? NON_ZERO_DIGIT DIGIT*
    ;

LONG
    : ZERO
    | ('-' | '+')? NON_ZERO_DIGIT DIGIT* 'L'
    ;

DOUBLE
    : ('-' | '+')? DIGIT+ (('.' DIGIT+ EXPONENT?)? | EXPONENT)
    ;

fragment EXPONENT
    : ('E' | 'e') '-'? NON_ZERO_DIGIT DIGIT*
    ;

ZERO
    : '0'
    ;

DIGIT
    : ('0' .. '9')
    ;

NON_ZERO_DIGIT
    : ('1' .. '9')
    ;

ARROW
    : '->'
    ;

COLON
    : ':'
    ;

EQUAL
    : '='
    ;

PLUS
    : '+'
    ;

MINUS
    : '-'
    ;

TIMES
    : '*'
    ;

DOLLAR
    : '$'
    ;

SEQ
    : 'seq'
    ;

IF
    : 'if'
    ;

PACKAGE
    : 'package'
    ;

EQ
    : '=='
    ;

NEQ
    : '!='
    ;

LT
    : '<'
    ;

GT
    : '>'
    ;

LE
    : '<='
    ;

GE
    : '>='
    ;

DIVIDE
    : '/'
    ;
    
LAMBDA
    : '\\'
    ;

LPAREN
    : '('
    ;

RPAREN
    : ')'
    ;

// Identifier. MeTTa names use a wider character set than typical Lisps:
//   * trailing '!' / '?' for predicate / effect conventions (`Frog?`, `bind!`)
//   * leading '.' for namespace-style symbols like PLN's `.tv`
//   * '%' on either end and inside, so `%Undefined%` is a single token
//   * a bare ',' is a valid name (e.g. the `match`-conjunction operator)
//   * leading '::' for cons-style names (`::`, `::foo`) — Lisp/Haskell convention
//     used in e.g. `c1_grounded_basic.metta` for list construction. ANTLR's
//     longest-match disambiguates: bare ':' is still the type-annotation COLON
//     token, but two-or-more colons start an IDENT.
// The leading '!' for statement-level evaluation (see `run`) is unaffected
// because '!' is not in the start-character class.
IDENT
    : [a-zA-Z_&.%,] [a-zA-Z0-9_\-%]* [!?]?
    | ':' ':' [a-zA-Z0-9_\-%]* [!?]?
    ;