#!/usr/bin/env bash
# Read-only export. Run only in an approved encrypted operator workspace OUTSIDE Git.
# Raw configuration can contain personal data in expressions; it is NOT sanitized evidence.
set +x
set -euo pipefail
umask 077
: "${CLOUDFLARE_API_TOKEN:?Set a zone-scoped read token in the environment}"
: "${CLOUDFLARE_ZONE_ID:?Set zone ID in the environment}"
[[ "$CLOUDFLARE_API_TOKEN" =~ ^[A-Za-z0-9_-]+$ ]] || exit 2
[[ "$CLOUDFLARE_ZONE_ID" =~ ^[a-f0-9]{32}$ ]] || exit 2
[[ $# == 1 && ! -e "$1" ]] || { echo 'Usage: export-zone.sh NEW_PRIVATE_DIRECTORY' >&2; exit 2; }
mkdir -m 700 -- "$1"
out=$(cd -- "$1" && pwd)
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
# Keep the token out of argv, stdout and retained files. Never follow API redirects.
printf 'header = "Authorization: Bearer %s"\n' "$CLOUDFLARE_API_TOKEN" > "$tmp/auth"
get() {
  local endpoint=$1 destination=$2
  curl --disable --config "$tmp/auth" --silent --fail --proto '=https' \
    --connect-timeout 10 --max-time 60 \
    "https://api.cloudflare.com/client/v4/zones/${CLOUDFLARE_ZONE_ID}${endpoint}" > "$tmp/response" || {
      echo 'ERROR: API request failed; export incomplete' >&2; exit 1;
    }
  python3 - "$tmp/response" "$destination" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
    if data.get('success') is not True or 'result' not in data:
        raise ValueError()
    info = data.get('result_info', {})
    if info.get('total_pages', 1) > 1:
        raise ValueError()
    with open(sys.argv[2], 'w') as output:
        json.dump(data['result'], output, indent=2, sort_keys=True)
        output.write('\n')
except (ValueError, OSError, TypeError):
    sys.exit('ERROR: invalid or paginated API response; export incomplete')
PY
}
get '' "$out/zone.private.txt"
get '/settings' "$out/settings.private.txt"
get '/bot_management' "$out/bots.private.txt"
get '/rulesets' "$out/rulesets.private.txt"
# Fetch full rules (not just list metadata), preserving evaluation order in arrays.
python3 - "$out/rulesets.private.txt" > "$tmp/ids" <<'PY'
import json, re, sys
items = json.load(open(sys.argv[1]))
ids = sorted({item['id'] for item in items})
if any(not re.fullmatch('[a-f0-9]{32}', item) for item in ids):
    sys.exit('ERROR: invalid ruleset IDs')
print('\n'.join(ids))
PY
while IFS= read -r id; do
  [[ -n "$id" ]] || continue
  get "/rulesets/$id" "$out/ruleset-$id.private.txt"
done < "$tmp/ids"
# Completion marker is written only after every required API read succeeds.
printf '%s\n' 'schema=1' 'scope=zone only; account policies and external lists require operator review' \
  'classification=RAW PRIVATE; manual privacy review required before sharing' > "$out/COMPLETE.txt"
echo 'PASS zone export complete; raw private configuration requires operator review'
