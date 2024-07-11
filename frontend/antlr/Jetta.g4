grammar Jetta;

program
    : expression* EOF
    ;

expression
    : LPAREN atom (atom)* RPAREN
    ;

atom
    : variable
    | symbol
    | number
    | special
    | expression
    ;

number
    : integer
    | double
    ;

integer
    : INTEGER
    ;

double
    : DOUBLE
    ;

special
    : pattern
    | type
    | arrow
    | plus
    | minus
    | times
    | if
    | eq
    | lambda
    ;

lambda
    : LAMBDA
    ;

eq
    : EQ
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

variable
    : DOLLAR identifier
    ;

identifier
    : IDENT
    ;

symbol
    : IDENT
    ;

WS
    : [ \t\r\n] -> skip
    ;

COMMENT
    : ';' .*? '\n' -> skip
    ;

INTEGER
    : ZERO
    | NON_ZERO_DIGIT DIGIT*
    ;

DOUBLE
    : DIGIT+ (('.' DIGIT+ EXPONENT?)? | EXPONENT)
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

IF
    : 'if'
    ;

EQ
    : '=='
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

IDENT
    : [a-zA-Z_] [a-zA-Z0-9_]*
    ;