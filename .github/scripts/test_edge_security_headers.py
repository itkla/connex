import shlex
import unittest
from pathlib import Path


CADDYFILE_PATH = Path(__file__).parents[2] / "deploy" / "Caddyfile"
SECURITY_HEADER_FIELDS = (
    "Content-Security-Policy",
    "Referrer-Policy",
    "X-Content-Type-Options",
    "X-Frame-Options",
)


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


if __name__ == "__main__":
    unittest.main()
