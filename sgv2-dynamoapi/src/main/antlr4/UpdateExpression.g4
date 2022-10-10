grammar UpdateExpression;

/*********************************************
    PARSER RULES
**********************************************/

expr                : stat                          # SingleStat
//                    | expr stat                     # MultiStat
                    ;

//testStat            : setStat1
//                    | removeStat1
//                    | addStat1
//                    | deleteStat1
//                    ;

stat                : SET setTerms                  # SetStat
                    | REMOVE names                  # RemoveStat
                    | ADD nameValuePairs            # AddStat
                    | DELETE nameValuePairs         # DeleteStat
                    ;

//setStat1             : SET setTerms
//                    ;
//
//removeStat1          : REMOVE names;
//
//addStat1             : ADD nameValuePairs;
//
//deleteStat1          : DELETE nameValuePairs;

setTerm             : name EQUAL value;

setTerms            : setTerm                           # SingleSet
                    | setTerm COMMA setTerms            # MultiSet
                    ;

names               : name                              # SingleName
                    | name COMMA names                  # MultiName
                    ;

values              : value                             # SingleVal
                    | value COMMA values                # MultiVal
                    ;
nameValuePairs      : name value                        # SinglePair
                    | name value COMMA nameValuePairs   # MultiPair
                    ;

name                : WORD | NAME_PLACEHOLDER;

value               : VALUE_PLACEHOLDER;

/*********************************************
    LEXER RULES
**********************************************/

fragment LOWERCASE  : [a-z] ;
fragment UPPERCASE  : [A-Z] ;
fragment DIGIT      : [0-9] ;

SET                 : 'SET';
REMOVE              : 'REMOVE';
ADD                 : 'ADD';
DELETE              : 'DELETE';
COMMA               : ',';
EQUAL               : '=';



// keywords
WORD                : (LOWERCASE | UPPERCASE | DIGIT | '[' | ']' | '.')+ ;
VALUE_PLACEHOLDER   : ':' WORD;
NAME_PLACEHOLDER    : '#' WORD;

// whitespace
WHITESPACE          :  [ \t\r\n\u000C]+ -> skip;
