package ooo.klae.connex.backend.support;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** SQL scope inspection shared by mapper contract tests. */
public final class SqlQueryScopes {

    private SqlQueryScopes() {
    }

    /** Removes SQL block comments and optimizer hints without consuming quoted strings. */
    public static String withoutComments(String sql) {
        return Pattern.compile("'(?:''|[^'])*'|/\\*.*?\\*/", Pattern.DOTALL).matcher(sql)
            .replaceAll(match -> match.group().startsWith("/*") ? " " : Matcher.quoteReplacement(match.group()));
    }

    /** Returns each SELECT arm independently, excluding its nested SELECT bodies. */
    public static List<String> selectScopes(String sql) {
        List<String> scopes = new ArrayList<>();
        String uncommented = withoutComments(sql);
        Matcher selects = Pattern.compile("'(?:''|[^'])*'|\\bSELECT\\b", Pattern.CASE_INSENSITIVE)
            .matcher(uncommented);
        while (selects.find()) {
            if (selects.group().equalsIgnoreCase("SELECT")) {
                scopes.add(selectScope(uncommented.substring(selects.end())));
            }
        }
        return scopes;
    }

    /** Keeps only WHERE, JOIN ON and HAVING expressions, excluding projection and ordering clauses. */
    public static String filteringClauses(String sql) {
        String uncommented = withoutComments(sql);
        Matcher tokens = Pattern.compile(
            "'(?:''|[^'])*'|[()]|\\b(?:WHERE|ON|HAVING|FROM|JOIN|GROUP\\s+BY|ORDER\\s+BY|WINDOW|LIMIT|FOR|SET|UNION)\\b",
            Pattern.CASE_INSENSITIVE).matcher(uncommented);
        StringBuilder filters = new StringBuilder();
        int depth = 0;
        int cursor = 0;
        boolean filtering = false;
        while (tokens.find()) {
            if (filtering) {
                filters.append(uncommented, cursor, tokens.start());
            }
            String token = tokens.group();
            if (token.equals("(")) {
                depth++;
            } else if (token.equals(")")) {
                depth--;
            } else if (!token.startsWith("'") && depth == 0) {
                filtering = Set.of("WHERE", "ON", "HAVING").contains(token.toUpperCase(Locale.ROOT));
            }
            if (filtering) {
                filters.append(token.startsWith("'") ? "''" : token).append(' ');
            } else {
                filters.append(' ');
            }
            cursor = tokens.end();
        }
        if (filtering) {
            filters.append(uncommented, cursor, uncommented.length());
        }
        return filters.toString();
    }

    /** Keeps a SELECT's projection, tables and predicates, excluding nested queries and UNION arms. */
    public static String selectScope(String sql) {
        Matcher tokens = Pattern.compile("'(?:''|[^'])*'|[()]|\\b(?:SELECT|UNION)\\b", Pattern.CASE_INSENSITIVE)
            .matcher(sql);
        StringBuilder scope = new StringBuilder();
        int depth = 0;
        int subqueryDepth = -1;
        int cursor = 0;
        while (tokens.find()) {
            if (subqueryDepth < 0) {
                scope.append(sql, cursor, tokens.start());
            }
            String token = tokens.group();
            if (token.equals("(")) {
                if (subqueryDepth < 0) {
                    scope.append('(');
                }
                depth++;
            } else if (token.equals(")")) {
                if (depth == 0) {
                    return scope.toString();
                }
                if (depth == subqueryDepth) {
                    subqueryDepth = -1;
                }
                depth--;
                if (subqueryDepth < 0) {
                    scope.append(')');
                }
            } else if (token.equalsIgnoreCase("SELECT") && depth > 0 && subqueryDepth < 0) {
                subqueryDepth = depth;
            } else if (token.equalsIgnoreCase("UNION") && depth == 0) {
                return scope.toString();
            } else if (subqueryDepth < 0) {
                scope.append("''");
            }
            cursor = tokens.end();
        }
        if (subqueryDepth < 0) {
            scope.append(sql, cursor, sql.length());
        }
        return scope.toString();
    }
}
