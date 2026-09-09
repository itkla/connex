package ooo.klae.connex.backend.apisurface;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Pattern;

import javax.tools.ToolProvider;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreeScanner;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.util.ReflectionUtils;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Controller;
import org.springframework.util.AntPathMatcher;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;

import ooo.klae.connex.backend.config.PublicApiSecurityConfig;
import ooo.klae.connex.backend.tenant.RequirePermission;

/** Generates the review ledger without starting Spring or connecting to a database. */
public final class ApiSurfaceInventory {
    public static final Path INVENTORY = Path.of("../docs/backend/api-surface.tsv");
    public static final Path POLICY = Path.of("../docs/backend/api-surface-policy.txt");
    private static final Path JAVA = Path.of("src/main/java");
    private static final String BASE = "ooo.klae.connex.backend";
    private static final Pattern STRINGS = Pattern.compile("\"([^\"]*)\"");
    private static final Pattern RULE = Pattern.compile(
        "\\.requestMatchers\\((.*?)\\)\\s*\\.(permitAll|authenticated|denyAll|hasAuthority)\\((.*?)\\)",
        Pattern.DOTALL);
    private final Map<Class<?>, Map<String, String>> bodies = new TreeMap<>(
        java.util.Comparator.comparing(Class::getName));
    private final List<Rule> rules = new ArrayList<>();
    private final List<String> excluded;

    public ApiSurfaceInventory() throws IOException {
        String security = source("config/SecurityConfig.java");
        var matcher = RULE.matcher(security.substring(security.indexOf(".authorizeHttpRequests")));
        while (matcher.find()) {
            String arguments = matcher.group(1);
            var method = Pattern.compile("HttpMethod\\.(\\w+)").matcher(arguments);
            String verb = method.find() ? method.group(1) : "*";
            for (String path : strings(arguments)) {
                rules.add(new Rule(verb, path, matcher.group(2), matcher.group(3)));
            }
        }
        if (rules.size() < 20) {
            throw new IllegalStateException("Security rule parser did not find the application policy");
        }
        String web = source("config/WebConfig.java");
        excluded = strings(web.substring(web.indexOf(".excludePathPatterns(")));
    }

    /** Includes conditional controllers even when their feature is disabled in production. */
    public List<Endpoint> endpoints() throws Exception {
        List<Endpoint> endpoints = new ArrayList<>();
        var resolver = new PathMatchingResourcePatternResolver();
        for (var resource : resolver.getResources("classpath*:ooo/klae/connex/backend/**/*.class")) {
            String url = resource.getURL().toString();
            String name = url.substring(url.indexOf("ooo/klae/connex/backend/"))
                .replace('/', '.').replaceAll("\\.class$", "");
            if (!Files.exists(JAVA.resolve(name.replaceAll("\\$.*", "").replace('.', '/') + ".java"))) {
                continue;
            }
            Class<?> controller = Class.forName(name, false, getClass().getClassLoader());
            if (!AnnotatedElementUtils.hasAnnotation(controller, Controller.class)) {
                continue;
            }
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(controller, RequestMapping.class);
            for (Method method : ReflectionUtils.getAllDeclaredMethods(controller)) {
                RequestMapping mapping = AnnotatedElementUtils.findMergedAnnotation(method, RequestMapping.class);
                if (mapping == null) {
                    continue;
                }
                RequestMethod[] verbs = mapping.method().length > 0 ? mapping.method()
                    : base != null && base.method().length > 0 ? base.method() : RequestMethod.values();
                for (String prefix : paths(base)) {
                    for (String suffix : paths(mapping)) {
                        String path = ("/" + prefix + (suffix.isEmpty() ? "" : "/" + suffix)).replaceAll("/{2,}", "/");
                        TreeSet<String> methods = new TreeSet<>();
                        Arrays.stream(verbs).map(Enum::name).forEach(methods::add);
                        if (methods.contains("GET")) {
                            methods.add("HEAD");
                        }
                        methods.add("OPTIONS");
                        for (String verb : methods) {
                            endpoints.add(new Endpoint(verb, path, controller.getName() + "#" + method.getName(),
                                posture(verb, path), tenant(verb, path), permissions(controller, method),
                                conditions(base, mapping, controller), !verb.equals("OPTIONS")
                                    && (method.isAnnotationPresent(Deprecated.class)
                                        || controller.isAnnotationPresent(Deprecated.class))));
                        }
                    }
                }
            }
        }
        endpoints.addAll(frameworkEndpoints());
        return endpoints.stream().distinct().sorted(java.util.Comparator.comparing(Endpoint::line)).toList();
    }

    /** Compares the declared perimeter, not controller success or domain token validity. */
    public String posture(String method, String path) {
        if (path.startsWith("/api/v1/")) {
            for (var rule : PublicApiSecurityConfig.routeRules()) {
                if (rule.path().equals(path) && rule.authorizationMethods().contains(HttpMethod.valueOf(method))) {
                    return "bearer:" + rule.authority() + "; disabled unless connex.public-api.enabled";
                }
            }
            return "public-api deny/preflight; disabled unless connex.public-api.enabled";
        }
        if (method.equals("POST") && path.equals("/api/csp-reports")) {
            return "permitAll:stateless CSP collector";
        }
        for (Rule rule : rules) {
            if ((rule.method().equals("*") || rule.method().equals(method))
                    && new AntPathMatcher().match(rule.path(), path)) {
                return rule.posture() + (rule.authority().isBlank() ? "" : ":" + rule.authority());
            }
        }
        return "authenticated";
    }

    private List<Endpoint> frameworkEndpoints() throws IOException {
        List<Endpoint> result = new ArrayList<>();
        String security = source("config/SecurityConfig.java");
        framework(result, security, "logoutUrl", "POST", "LogoutFilter", "CSRF; session invalidation", "");
        framework(result, security, "a.baseUri", "*", "OAuth2AuthorizationRequestRedirectFilter", "permitAll; OAuth enabled", "/{registrationId}");
        framework(result, security, "r.baseUri", "*", "OAuth2LoginAuthenticationFilter", "permitAll; OAuth state/code verification", "");
        framework(result, security, "loginProcessingUrl", "*", "Saml2WebSsoAuthenticationFilter", "permitAll; SSO enabled; signed solicited response", "");
        framework(result, source("config/WebSocketConfig.java"), "addEndpoint", "GET", "WebSocketHttpRequestHandler", "authenticated; session-bound WebSocket handshake", "");
        var saml = rules.stream().filter(rule -> rule.path().equals("/saml2/**")).toList();
        if (!saml.isEmpty()) {
            for (String method : frameworkMethods("*")) {
                for (String path : List.of("/saml2/authenticate/{registrationId}", "/saml2/authenticate")) {
                    result.add(new Endpoint(method, path,
                        "Saml2WebSsoAuthenticationRequestFilter", "permitAll; SSO enabled",
                        "no MVC workspace resolution", "SAML relying-party registration",
                        "Spring default; registrationId path variable or query parameter; CSRF policy applies", false));
                }
            }
        }
        return result;
    }

    private static void framework(List<Endpoint> result, String source, String setter, String method,
            String handler, String posture, String suffix) {
        var matcher = Pattern.compile(Pattern.quote(setter) + "\\(\"([^\"]+)\"\\)").matcher(source);
        if (!matcher.find()) {
            throw new IllegalStateException("Framework route setter changed: " + setter);
        }
        for (String verb : frameworkMethods(method)) {
            result.add(new Endpoint(verb, matcher.group(1) + suffix, handler, posture,
                "no MVC workspace resolution; handler-owned scope", "framework authentication protocol",
                "filter/handshake route; CSRF policy and protocol validation apply", false));
        }
    }

    private static List<String> frameworkMethods(String method) {
        return method.equals("*") ? List.of("DELETE", "GET", "HEAD", "OPTIONS", "PATCH", "POST", "PUT")
            : List.of(method);
    }

    private static String conditions(RequestMapping base, RequestMapping method, Class<?> controller) {
        List<String> result = new ArrayList<>();
        for (RequestMapping mapping : base == null ? List.of(method) : List.of(base, method)) {
            result.add("params=" + Arrays.toString(mapping.params()) + ";headers=" + Arrays.toString(mapping.headers())
                + ";version=" + mapping.version() + ";consumes=" + Arrays.toString(mapping.consumes()) + ";produces=" + Arrays.toString(mapping.produces()));
        }
        ConditionalOnProperty condition = controller.getAnnotation(ConditionalOnProperty.class);
        if (condition != null) {
            result.add("feature=" + condition.prefix() + ":" + Arrays.toString(condition.name())
                + "=" + condition.havingValue() + ";matchIfMissing=" + condition.matchIfMissing());
        }
        return String.join(" / ", result);
    }

    private String tenant(String method, String path) {
        if (path.startsWith("/api/v1/")) {
            return "credential-bound workspace";
        }
        if (!path.startsWith("/api/") || excluded.stream().anyMatch(p -> new AntPathMatcher().match(p, path))) {
            return "no MVC workspace resolution; domain scope if applicable";
        }
        if ((method.equals("DELETE") && (path.equals("/api/orgs/{orgId}")
                || path.equals("/api/orgs/{orgId}/workspaces/{workspaceId}")))
                || (method.equals("GET") && path.equals("/api/orgs/{orgId}/workspaces/{workspaceId}/export"))) {
            return "explicit lifecycle organization/workspace authorization";
        }
        return "MVC workspace resolution attempted for authenticated User; effective scope delegated";
    }

    private String permissions(Class<?> controller, Method endpoint) throws Exception {
        TreeSet<String> evidence = new TreeSet<>();
        RequirePermission own = endpoint.getAnnotation(RequirePermission.class);
        if (own != null) {
            evidence.add(own.value().name());
        }
        String body = methodBodies(controller).getOrDefault(endpoint.getName(), "");
        Pattern.compile("\\bPermission\\.(\\w+)").matcher(body).results()
            .map(match -> "explicit:" + match.group(1)).forEach(evidence::add);
        for (var field : controller.getDeclaredFields()) {
            if (!field.getType().getName().startsWith(BASE)) {
                continue;
            }
            var calls = Pattern.compile("\\b" + Pattern.quote(field.getName()) + "\\.(\\w+)\\s*\\(").matcher(body);
            while (calls.find()) {
                String called = calls.group(1);
                for (Method serviceMethod : field.getType().getMethods()) {
                    if (serviceMethod.getName().equals(called)) {
                        RequirePermission permission = serviceMethod.getAnnotation(RequirePermission.class);
                        evidence.add(field.getType().getSimpleName() + "#" + called + ":"
                            + (permission == null ? "domain authorization" : permission.value().name()));
                    }
                }
            }
        }
        return evidence.isEmpty() ? "controller/filter boundary; no direct annotated service call" : String.join("; ", evidence);
    }

    private Map<String, String> methodBodies(Class<?> type) throws IOException {
        if (bodies.containsKey(type)) {
            return Objects.requireNonNull(bodies.get(type));
        }
        Path file = JAVA.resolve(type.getName().replaceAll("\\$.*", "").replace('.', '/') + ".java");
        Map<String, String> methods = new TreeMap<>();
        var compiler = Objects.requireNonNull(ToolProvider.getSystemJavaCompiler(), "Inventory generation requires a JDK");
        try (var manager = compiler.getStandardFileManager(null, null, StandardCharsets.UTF_8)) {
            JavacTask task = (JavacTask) compiler.getTask(
                null, manager, null, List.of("-proc:none"), null, manager.getJavaFileObjects(file));
            for (var unit : task.parse()) {
                new TreeScanner<Void, Void>() {
                    @Override
                    public Void visitClass(ClassTree tree, Void unused) {
                        if (tree.getSimpleName().contentEquals(type.getSimpleName())) {
                            for (var member : tree.getMembers()) {
                                if (member instanceof MethodTree method && method.getBody() != null) {
                                    methods.merge(method.getName().toString(), method.getBody().toString(), String::concat);
                                }
                            }
                        }
                        return super.visitClass(tree, unused);
                    }
                }.scan(unit, null);
            }
        }
        bodies.put(type, methods);
        return methods;
    }

    /** Stable evidence pins filters, routing and service authorization as well as mapped URLs. */
    public String policy() throws Exception {
        TreeSet<Path> files = new TreeSet<>();
        try (var paths = Files.walk(JAVA)) {
            paths.filter(p -> p.toString().endsWith(".java")).filter(p -> {
                String name = p.getFileName().toString();
                return name.contains("Filter") || name.contains("SecurityConfig") || name.equals("WebConfig.java") || p.toString().contains("/config/") || p.toString().contains("/controllers/")
                    || p.toString().contains("/tenant/") || p.toString().contains("/services/");
            }).forEach(files::add);
        }
        files.add(Path.of("build.gradle"));
        files.add(Path.of("../frontend/next.config.ts"));
        files.add(Path.of("src/main/resources/application.yml"));
        StringBuilder result = new StringBuilder("# Generated perimeter and authorization source SHA-256 ledger\n");
        for (Path file : files) {
            result.append(file).append('\t').append(HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file)))).append('\n');
        }
        result.append("\n# Ordered application authorization matchers (conditional SSO/OAuth included)\n");
        for (Rule rule : rules) {
            result.append(rule).append('\n');
        }
        return result.toString();
    }

    public String inventory() throws Exception {
        StringBuilder result = new StringBuilder(
            "method\tpath\tcontroller\tauthentication\ttenant_scope_evidence\tauthorization_evidence\tmapping_conditions\tdeprecated\tversion\towner\tusage\teol\n");
        for (Endpoint endpoint : endpoints()) {
            result.append(endpoint.line()).append('\n');
        }
        return result.toString();
    }

    /** Explicit regeneration is separate from the assertion; CI never approves its own changes. */
    public static void main(String[] args) throws Exception {
        ApiSurfaceInventory generator = new ApiSurfaceInventory();
        Files.writeString(INVENTORY, generator.inventory());
        Files.writeString(POLICY, generator.policy());
        System.out.println("Generated API surface and policy ledgers");
    }

    private static String source(String path) throws IOException {
        return Files.readString(JAVA.resolve(BASE.replace('.', '/')).resolve(path));
    }

    private static List<String> strings(String expression) {
        return STRINGS.matcher(expression).results().map(match -> match.group(1)).toList();
    }

    private static List<String> paths(RequestMapping mapping) {
        return mapping == null || mapping.path().length == 0 ? List.of("") : List.of(mapping.path());
    }

    private record Rule(String method, String path, String posture, String authority) { }

    public record Endpoint(String method, String path, String controller, String authentication,
            String tenantScope, String permissions, String conditions, boolean deprecated) {
        public String line() {
            var versionMatch = Pattern.compile("^/api/(v[0-9]+)(?:/|$)").matcher(path);
            String version = versionMatch.find() ? versionMatch.group(1) : "unversioned";
            return String.join("\t", method, path, controller, authentication, tenantScope, permissions,
                conditions.replace('\t', ' ').replace('\n', ' '), Boolean.toString(deprecated),
                version, "Backend maintainers",
                "not measured; see API_SURFACE.md", deprecated ? "required in api-lifecycle.tsv" : "not scheduled");
        }
    }
}
