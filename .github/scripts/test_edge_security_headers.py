import shlex
import unittest
from pathlib import Path


CADDYFILE_PATH = Path(__file__).parents[2] / "deploy" / "Caddyfile"
EDGE_DEFENCE_PATH = Path(__file__).parents[2] / "docs" / "EDGE_DEFENCE.md"
SECURITY_HEADER_FIELDS = (
    "Content-Security-Policy",
    "Referrer-Policy",
    "Strict-Transport-Security",
    "X-Content-Type-Options",
    "X-Frame-Options",
)
CLOUDFLARE_PROXY_RANGES = (
    "173.245.48.0/20",
    "103.21.244.0/22",
    "103.22.200.0/22",
    "103.31.4.0/22",
    "141.101.64.0/18",
    "108.162.192.0/18",
    "190.93.240.0/20",
    "188.114.96.0/20",
    "197.234.240.0/22",
    "198.41.128.0/17",
    "162.158.0.0/15",
    "104.16.0.0/13",
    "104.24.0.0/14",
    "172.64.0.0/13",
    "131.0.72.0/22",
    "2400:cb00::/32",
    "2606:4700::/32",
    "2803:f800::/32",
    "2405:b500::/32",
    "2405:8100::/32",
    "2a06:98c0::/29",
    "2c0f:f248::/32",
)


def tokenized_caddyfile(hsts_enabled: bool | None = None) -> list[list[str]]:
    caddyfile = CADDYFILE_PATH.read_text(encoding="utf-8")
    if hsts_enabled is not None:
        caddyfile = caddyfile.replace(
            "{$CONNEX_CADDY_HSTS_ENABLED:false}",
            str(hsts_enabled).lower(),
        )
    return [
        tokens
        for raw_line in caddyfile.splitlines()
        if (tokens := shlex.split(raw_line, comments=True, posix=True))
    ]


def request_body_limit_for_handle(matcher: str | None) -> str:
    target = ["handle", "{"] if matcher is None else ["handle", matcher, "{"]
    handle_depth: int | None = None
    request_body_depth: int | None = None
    depth = 0

    for raw_line in CADDYFILE_PATH.read_text(encoding="utf-8").splitlines():
        tokens = shlex.split(raw_line, comments=True, posix=True)
        if tokens == target and handle_depth is None:
            handle_depth = depth + 1
        elif handle_depth is not None and depth < handle_depth:
            break
        elif handle_depth is not None and depth == handle_depth and tokens == ["request_body", "{"]:
            request_body_depth = depth + 1
        elif request_body_depth is not None and depth == request_body_depth and tokens[:1] == ["max_size"]:
            if len(tokens) != 2:
                raise AssertionError(f"invalid max_size directive under {' '.join(target)}")
            return tokens[1]

        depth += tokens.count("{") - tokens.count("}")

    raise AssertionError(f"no request_body max_size under {' '.join(target)}")


def direct_route_handles() -> list[str | None]:
    route_depth: int | None = None
    depth = 0
    handles: list[str | None] = []

    for raw_line in CADDYFILE_PATH.read_text(encoding="utf-8").splitlines():
        tokens = shlex.split(raw_line, comments=True, posix=True)
        if tokens == ["route", "{"] and depth == 1:
            route_depth = depth + 1
        elif route_depth is not None and depth < route_depth:
            break
        elif route_depth is not None and depth == route_depth:
            if tokens == ["handle", "{"]:
                handles.append(None)
            elif len(tokens) == 3 and tokens[0] == "handle" and tokens[2] == "{":
                handles.append(tokens[1])

        depth += tokens.count("{") - tokens.count("}")

    return handles


def direct_child_header_operations(parent: str, parent_depth: int) -> list[list[str]]:
    lines = CADDYFILE_PATH.read_text(encoding="utf-8").splitlines()
    block_depth: int | None = None
    header_depth: int | None = None
    header_found = False
    depth = 0
    operations: list[list[str]] = []

    for raw_line in lines:
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue

        if line == parent and depth == parent_depth:
            block_depth = depth + 1
        elif block_depth is not None and depth < block_depth:
            block_depth = None
        elif block_depth is not None and depth == block_depth and line == "header {":
            if header_found:
                raise AssertionError(f"deploy/Caddyfile declares multiple direct header blocks under {parent}")
            header_depth = depth + 1
            header_found = True
        elif header_depth is not None and depth == header_depth and line == "}":
            header_depth = None
        elif header_depth is not None and depth == header_depth and line != "}":
            operations.append(shlex.split(line, comments=True, posix=True))

        depth += raw_line.count("{") - raw_line.count("}")

    if not header_found:
        raise AssertionError(f"deploy/Caddyfile must declare a direct header block under {parent}")
    return operations


def security_header_mentions() -> dict[str, int]:
    mentions = {field: 0 for field in SECURITY_HEADER_FIELDS}
    canonical_fields = {field.lower(): field for field in SECURITY_HEADER_FIELDS}
    for raw_line in CADDYFILE_PATH.read_text(encoding="utf-8").splitlines():
        tokens = shlex.split(raw_line, comments=True, posix=True)
        for token in tokens:
            field = canonical_fields.get(token.lstrip("+?->").lower())
            if field is not None:
                mentions[field] += 1
    return mentions


def has_direct_child_directive(parent: str, parent_depth: int, directive: str) -> bool:
    block_depth: int | None = None
    depth = 0

    for raw_line in CADDYFILE_PATH.read_text(encoding="utf-8").splitlines():
        tokens = shlex.split(raw_line, comments=True, posix=True)
        line = " ".join(tokens)
        if line == parent and depth == parent_depth:
            block_depth = depth + 1
        elif block_depth is not None and depth < block_depth:
            block_depth = None
        elif block_depth is not None and depth == block_depth and tokens and tokens[0] == directive:
            return True

        depth += tokens.count("{") - tokens.count("}")

    return False


def documented_cloudflare_expression(rule_id: str) -> str:
    lines = EDGE_DEFENCE_PATH.read_text(encoding="utf-8").splitlines()
    rule_index = lines.index(rule_id)
    for line in lines[rule_index + 1:]:
        if line:
            return line
    raise AssertionError(f"{rule_id} has no documented expression")


class EdgeSecurityHeadersTest(unittest.TestCase):
    def test_site_level_headers_cover_every_edge_response(self) -> None:
        operations = direct_child_header_operations(":80 {", 0)
        by_field = {operation[0].lstrip("+?->"): operation for operation in operations}

        self.assertEqual(len(operations), len(by_field), "header fields must not be duplicated")
        self.assertEqual(
            [">X-Content-Type-Options", "nosniff"],
            by_field["X-Content-Type-Options"],
        )
        self.assertEqual(
            [">Referrer-Policy", "strict-origin-when-cross-origin"],
            by_field["Referrer-Policy"],
        )
        self.assertEqual([">X-Frame-Options", "DENY"], by_field["X-Frame-Options"])

        content_security_policy = by_field["Content-Security-Policy"]
        self.assertEqual("?Content-Security-Policy", content_security_policy[0])
        self.assertEqual("frame-ancestors 'none'", content_security_policy[1])

    def test_caddy_error_route_reapplies_the_header_contract(self) -> None:
        operations = direct_child_header_operations("handle_errors {", 1)

        self.assertEqual(
            [
                ["X-Content-Type-Options", "nosniff"],
                ["Referrer-Policy", "strict-origin-when-cross-origin"],
                ["X-Frame-Options", "DENY"],
                ["Content-Security-Policy", "frame-ancestors 'none'"],
            ],
            operations,
        )
        self.assertTrue(
            has_direct_child_directive("handle_errors {", 1, "respond"),
            "handle_errors must produce the hardened fallback response",
        )

    def test_security_headers_are_not_redeclared_elsewhere(self) -> None:
        self.assertEqual(
            {field: 2 for field in SECURITY_HEADER_FIELDS},
            security_header_mentions(),
            "security headers must appear only in the site and error-handler contract blocks",
        )

    def test_hsts_is_present_only_when_explicitly_enabled(self) -> None:
        hsts_header = [
            "header",
            "@hsts",
            ">Strict-Transport-Security",
            "max-age=31536000; includeSubDomains",
        ]
        disabled_lines = tokenized_caddyfile(hsts_enabled=False)
        enabled_lines = tokenized_caddyfile(hsts_enabled=True)

        self.assertIn(["@hsts", "expression", "false", "==", "true"], disabled_lines)
        self.assertIn(["@hsts", "expression", "true", "==", "true"], enabled_lines)
        self.assertEqual(2, disabled_lines.count(hsts_header))
        self.assertEqual(2, enabled_lines.count(hsts_header))

    def test_server_bounds_headers_and_slow_requests(self) -> None:
        lines = tokenized_caddyfile()
        self.assertIn(["read_header", "10s"], lines)
        self.assertIn(["read_body", "5m"], lines)
        self.assertIn(["idle", "2m"], lines)
        self.assertIn(["max_header_size", "64KiB"], lines)

    def test_only_cloudflare_and_explicit_additional_proxies_are_trusted(self) -> None:
        lines = tokenized_caddyfile()
        trusted_proxies = [line for line in lines if line[0] == "trusted_proxies"]
        self.assertEqual(1, len(trusted_proxies))
        self.assertEqual(
            [
                "trusted_proxies",
                "static",
                *CLOUDFLARE_PROXY_RANGES,
                "{$CONNEX_CADDY_ADDITIONAL_TRUSTED_PROXIES:}",
            ],
            trusted_proxies[0],
        )
        self.assertIn(["trusted_proxies_strict"], lines)
        self.assertIn(
            ["client_ip_headers", "CF-Connecting-IP", "X-Forwarded-For"],
            lines,
        )

    def test_route_specific_request_body_limits_match_application_contracts(self) -> None:
        caddyfile = CADDYFILE_PATH.read_text(encoding="utf-8")
        self.assertEqual(
            [
                "@imports",
                "@uploads",
                "@business_cards",
                "@client_errors",
                "@webauthn",
                "@workflows",
                "@saml",
                "/api/*",
                None,
            ],
            direct_route_handles(),
        )
        expected_limits = {
            "@imports": "{$CONNEX_IMPORT_MAX_BODY_BYTES:67108864}",
            "@uploads": "{$CONNEX_UPLOAD_MAX_BODY_BYTES:28311552}",
            "@business_cards": "{$CONNEX_BUSINESS_CARD_MAX_BODY_BYTES:12582912}",
            "@client_errors": "{$CONNEX_CLIENT_ERRORS_MAX_BODY_BYTES:16384}",
            "@webauthn": "{$CONNEX_WEBAUTHN_MAX_BODY_BYTES:65536}",
            "@workflows": "{$CONNEX_WORKFLOW_MAX_BODY_BYTES:98304}",
            "@saml": "{$CONNEX_FORM_MAX_BODY_BYTES:1048576}",
            "/api/*": "{$CONNEX_API_MAX_BODY_BYTES:10485760}",
        }
        for matcher, limit in expected_limits.items():
            with self.subTest(matcher=matcher):
                self.assertEqual(limit, request_body_limit_for_handle(matcher))
                if matcher != "/api/*":
                    self.assertLess(
                        caddyfile.index(f"handle {matcher} {{"),
                        caddyfile.index("handle /api/* {"),
                    )
        self.assertEqual(
            "{$CONNEX_FORM_MAX_BODY_BYTES:1048576}",
            request_body_limit_for_handle(None),
        )
        self.assertIn(
            "^/api/(?:attachments/upload|ai/assistant/sessions/[0-9]+/attachments|"
            "users/me/profile-picture|persons/[0-9]+/profile-picture|companies/[0-9]+/logo)$",
            caddyfile,
        )
        self.assertIn("@saml path /saml2/* /api/login/saml2/sso/*", caddyfile)

    def test_backend_receives_only_caddy_resolved_client_ip(self) -> None:
        lines = tokenized_caddyfile()
        self.assertIn(["header_up", "X-Forwarded-For", "{client_ip}"], lines)
        self.assertIn(["header_up", "-CF-Connecting-IP"], lines)
        self.assertEqual(8, lines.count(["import", "backend_proxy"]))

    def test_authoritative_cloudflare_rate_rules_and_exclusions_are_documented(self) -> None:
        edge_defence = EDGE_DEFENCE_PATH.read_text(encoding="utf-8")
        expected_rate_rules = {
            "CF-RL-01-AUTH-ASSERT": "30 requests / 60 seconds / IP",
            "CF-RL-02-ACCOUNT-LIFECYCLE": "20 requests / 60 seconds / IP",
            "CF-RL-03-AI": "60 requests / 60 seconds / IP",
            "CF-RL-04-UPLOADS": "30 requests / 60 seconds / IP",
            "CF-RL-05-API-VOLUME": "1,200 requests / 60 seconds / IP",
        }
        for rule_id, threshold in expected_rate_rules.items():
            with self.subTest(rule_id=rule_id):
                self.assertIn(f"`{rule_id}`", edge_defence)
                self.assertIn(threshold, edge_defence)
        for exception_id in (
            "CF-EX-01-WEBSOCKET",
            "CF-EX-02-TOKEN-CALLBACKS",
            "CF-EX-03-SAML",
            "CF-EX-04-UPLOADS",
        ):
            with self.subTest(exception_id=exception_id):
                self.assertIn(f"`{exception_id}`", edge_defence)
        self.assertIn("`CF-CONFIG-01-COMPATIBILITY`", edge_defence)
        self.assertIn("`CF-REDIRECT-01-PRODUCTION-HTTPS`", edge_defence)
        self.assertIn("`CF-REDIRECT-02-PREVIEW-HTTPS`", edge_defence)
        self.assertIn("Request URL `http://connexcrm.jp/*`", edge_defence)
        self.assertIn("Request URL `http://preview.connexcrm.jp/*`", edge_defence)
        self.assertIn("only after both hostnames pass", edge_defence)
        self.assertIn(
            "| Definitely Automated | Managed Challenge after staging evidence | "
            "Managed Challenge after staging evidence |",
            edge_defence,
        )
        self.assertNotIn("Allow, as required for Tunnel connectivity", edge_defence)

        upload_method_scope = 'http.request.method in {"POST" "PUT"} and ('
        self.assertIn(
            upload_method_scope,
            documented_cloudflare_expression("CF-RL-04-UPLOADS"),
        )
        self.assertIn(
            upload_method_scope,
            documented_cloudflare_expression("CF-RL-05-API-VOLUME"),
        )
        self.assertIn(
            "`GET`, `HEAD`, `DELETE`, and every other method on those path families remain",
            edge_defence,
        )

    def test_stock_caddy_rate_limit_boundary_is_explicit(self) -> None:
        self.assertNotIn("rate_limit", {line[0] for line in tokenized_caddyfile()})
        edge_defence = EDGE_DEFENCE_PATH.read_text(encoding="utf-8")
        self.assertIn("does not contain `http.handlers.rate_limit`", edge_defence)
        self.assertIn("managed Cloudflare rate rules", edge_defence)


if __name__ == "__main__":
    unittest.main()
