#!/usr/bin/env bash
set -euo pipefail
# GET only, no cookies, credentials, redirects followed, or rate-limit traffic.
# Python parses headers without retaining cookies, Ray IDs, URLs, or response bodies.
exec python3 "$(dirname "$0")/public_headers.py" "$@"
