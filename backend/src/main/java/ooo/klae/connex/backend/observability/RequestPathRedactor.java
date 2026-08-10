package ooo.klae.connex.backend.observability;

import java.util.Set;

/** Maps reported paths to a closed, server-owned frontend route-template vocabulary. */
public final class RequestPathRedactor {
    public static final String UNKNOWN_ROUTE = "unknown";

    private static final Set<String> LOCALES = Set.of("en", "ja");
    private static final Set<String> ROUTE_TEMPLATES = Set.of(
        "/",
        "/account",
        "/account/connections",
        "/account/connections/reviews",
        "/account/invites",
        "/account/notifications",
        "/account/profile",
        "/account/security",
        "/activity/activities/{id}",
        "/activity/all",
        "/activity/notes",
        "/activity/notes/{id}",
        "/activity/tasks",
        "/activity/tasks/{id}",
        "/admin/logs",
        "/auth/{action}",
        "/auth/confirm-email",
        "/auth/forgot-password",
        "/auth/login",
        "/auth/logout",
        "/auth/register",
        "/auth/reset-password",
        "/auth/verify-email",
        "/dashboard",
        "/design-system",
        "/disclosure",
        "/docs",
        "/docs/{...slug}",
        "/invite/{token}",
        "/invite-link/{token}",
        "/legal",
        "/library/documents",
        "/library/documents/{id}",
        "/library/documents/new",
        "/library/files",
        "/library/tags",
        "/marketing/campaigns",
        "/marketing/campaigns/{id}",
        "/me",
        "/notifications",
        "/onboarding",
        "/organization",
        "/organization/ai",
        "/organization/allowed-domains",
        "/organization/audit",
        "/organization/data-requests",
        "/organization/diagnostics",
        "/organization/members",
        "/organization/overview",
        "/organization/sso",
        "/overview/analytics",
        "/overview/calendar",
        "/overview/introductions",
        "/overview/map",
        "/overview/reports",
        "/overview/reports/{id}",
        "/overview/reports/{id}/edit",
        "/overview/reports/{id}/snapshots",
        "/overview/reports/{id}/snapshots/{snapshotId}",
        "/overview/reports/goals",
        "/overview/reports/new",
        "/privacy",
        "/records/approval-policies",
        "/records/companies",
        "/records/companies/{id}",
        "/records/contacts",
        "/records/contacts/{id}",
        "/records/deals",
        "/records/deals/{id}",
        "/records/deals/{id}/documents/{docId}/print",
        "/records/pipelines",
        "/records/products",
        "/search",
        "/settings",
        "/settings/custom-fields",
        "/settings/data",
        "/settings/delivery",
        "/settings/diagnostics",
        "/settings/email",
        "/settings/general",
        "/settings/members",
        "/settings/membership",
        "/settings/notifications",
        "/settings/roles",
        "/settings/rules",
        "/settings/security",
        "/settings/sso",
        "/settings/workflows/{legacyRuleId}",
        "/sso/link",
        "/tokushoho",
        "/unsubscribe/{token}",
        "/users",
        "/users/{id}",
        "/workflows",
        "/workflows/{workflowId}",
        "/workflows/{workflowId}/runs/{runKey}",
        "/workflows/new",
        "/workflows/operations",
        "/workflows/recipes",
        "/workflows/recipes/{recipeKey}");

    private RequestPathRedactor() {
    }

    /**
     * Returns a recognized route template, {@link #UNKNOWN_ROUTE}, or null when the input was null.
     *
     * @param path the caller-controlled path
     * @return the closed-vocabulary route template
     */
    public static String redact(String path) {
        if (path == null) {
            return null;
        }
        String pathname = path.substring(0, suffixStart(path));
        String template = recognizedTemplate(pathname);
        if (template != null) {
            return template;
        }
        String[] segments = segments(pathname);
        if (segments.length > 1
                && (LOCALES.contains(segments[0]) || "{locale}".equals(segments[0]))) {
            String localizedPath = "/" + String.join("/", java.util.Arrays.copyOfRange(
                segments, 1, segments.length));
            String localizedTemplate = recognizedTemplate(localizedPath);
            if (localizedTemplate != null) {
                return "/{locale}" + localizedTemplate;
            }
        }
        return UNKNOWN_ROUTE;
    }

    private static String recognizedTemplate(String pathname) {
        String recognized = null;
        int recognizedSpecificity = -1;
        for (String template : ROUTE_TEMPLATES) {
            int specificity = specificity(template);
            if (specificity > recognizedSpecificity && matches(pathname, template)) {
                recognized = template;
                recognizedSpecificity = specificity;
            }
        }
        return recognized;
    }

    private static int specificity(String template) {
        int literalSegments = 0;
        for (String segment : segments(template)) {
            if (!segment.startsWith("{")) {
                literalSegments++;
            }
        }
        return literalSegments;
    }

    private static boolean matches(String pathname, String template) {
        String[] actualSegments = segments(pathname);
        String[] templateSegments = segments(template);
        boolean catchAll = templateSegments.length > 0
            && "{...slug}".equals(templateSegments[templateSegments.length - 1]);
        if ((!catchAll && actualSegments.length != templateSegments.length)
                || (catchAll && actualSegments.length < templateSegments.length)) {
            return false;
        }
        int fixedSegments = catchAll ? templateSegments.length - 1 : templateSegments.length;
        for (int index = 0; index < fixedSegments; index++) {
            String expected = templateSegments[index];
            String actual = actualSegments[index];
            if (expected.startsWith("{") && expected.endsWith("}")) {
                if (actual.isEmpty()) {
                    return false;
                }
            } else if (!expected.equals(actual)) {
                return false;
            }
        }
        return true;
    }

    private static String[] segments(String pathname) {
        if ("/".equals(pathname)) {
            return new String[0];
        }
        if (!pathname.startsWith("/") || pathname.endsWith("/")) {
            return new String[] { pathname };
        }
        return pathname.substring(1).split("/", -1);
    }

    private static int suffixStart(String path) {
        int query = path.indexOf('?');
        int fragment = path.indexOf('#');
        if (query < 0) {
            return fragment < 0 ? path.length() : fragment;
        }
        if (fragment < 0) {
            return query;
        }
        return Math.min(query, fragment);
    }
}
