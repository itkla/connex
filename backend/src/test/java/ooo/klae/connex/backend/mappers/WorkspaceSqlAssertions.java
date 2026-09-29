package ooo.klae.connex.backend.mappers;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.ibatis.mapping.BoundSql;

import ooo.klae.connex.backend.support.SqlQueryScopes;

/** Checks workspace parameter bindings independently in each query or mutation scope. */
final class WorkspaceSqlAssertions {

    private WorkspaceSqlAssertions() {
    }

    static void assertWorkspacePredicates(BoundSql bound, String statement) {
        String sql = SqlQueryScopes.withoutComments(bound.getSql());
        Matcher placeholders = Pattern.compile("'(?:''|[^'])*'|\\?").matcher(sql);
        StringBuilder namedSql = new StringBuilder();
        int binding = 0;
        while (placeholders.find()) {
            if (placeholders.group().equals("?")) {
                String property = bound.getParameterMappings().get(binding++).getProperty();
                placeholders.appendReplacement(namedSql, Matcher.quoteReplacement("#{" + property + "}"));
            }
        }
        placeholders.appendTail(namedSql);
        assertEquals(bound.getParameterMappings().size(), binding, statement);

        List<String> scopes = new ArrayList<>(SqlQueryScopes.selectScopes(namedSql.toString()));
        Matcher mutations = Pattern.compile("'(?:''|[^'])*'|\\bFOR\\s+UPDATE\\b|\\b(UPDATE|DELETE)\\b",
            Pattern.CASE_INSENSITIVE).matcher(namedSql);
        while (mutations.find()) {
            if (mutations.group(1) != null) {
                scopes.add(SqlQueryScopes.selectScope(namedSql.substring(mutations.end())));
            }
        }
        assertFalse(scopes.isEmpty(), statement + " must contain a query or mutation scope");
        Pattern predicate = Pattern.compile("(?i:\\bworkspace_id\\s*=\\s*)#\\{workspaceId\\}");
        for (String scope : scopes) {
            assertTrue(predicate.matcher(SqlQueryScopes.filteringClauses(scope)).find(),
                statement + " must predicate on workspaceId in every scope: " + scope);
        }
    }
}
