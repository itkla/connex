#!/usr/bin/env bash
# Query only the approved fields; raw API content is temporary and never printed.
# Use an encrypted operator workspace/TMPDIR and a reviewed rule ID -> stable label map.
set +x
set -euo pipefail
umask 077
: "${CLOUDFLARE_API_TOKEN:?Set zone analytics read token}"
: "${CLOUDFLARE_ZONE_ID:?Set zone ID}"
[[ "$CLOUDFLARE_API_TOKEN" =~ ^[A-Za-z0-9_-]+$ && "$CLOUDFLARE_ZONE_ID" =~ ^[a-f0-9]{32}$ ]] || exit 2
[[ $# == 3 ]] || { echo 'Usage: capture-events.sh UTC_START UTC_END PRIVATE_RULE_MAP' >&2; exit 2; }
tmp=$(mktemp -d)
trap 'rm -rf -- "$tmp"' EXIT
printf 'header = "Authorization: Bearer %s"\n' "$CLOUDFLARE_API_TOKEN" > "$tmp/auth"
python3 - "$1" "$2" > "$tmp/query" <<'PY'
import datetime, json, os, sys
try:
    start, end = [datetime.datetime.strptime(v, '%Y-%m-%dT%H:%M:%SZ') for v in sys.argv[1:]]
    if not 0 < (end - start).total_seconds() <= 3600:
        raise ValueError()
except ValueError:
    sys.exit('ERROR: require UTC interval of at most one hour')
query = '''query($zoneTag: string, $start: Time, $end: Time) {
 viewer { zones(filter: {zoneTag: $zoneTag}) {
 firewallEventsAdaptive(limit: 10000, orderBy: [datetime_ASC],
 filter: {datetime_geq: $start, datetime_lt: $end}) {
 datetime clientRequestHTTPHost clientRequestHTTPMethodName action source ruleId
 } } } }'''
print(json.dumps({'query': query, 'variables': {'zoneTag': os.environ['CLOUDFLARE_ZONE_ID'],
                                              'start': sys.argv[1], 'end': sys.argv[2]}}))
PY
curl --disable --config "$tmp/auth" --silent --fail --proto '=https' \
  --connect-timeout 10 --max-time 60 --max-filesize 33554432 \
  -H 'Content-Type: application/json' --data-binary "@$tmp/query" \
  https://api.cloudflare.com/client/v4/graphql > "$tmp/response" || {
    echo 'ERROR: analytics API request failed' >&2; exit 1;
  }
python3 - "$tmp/response" > "$tmp/events" <<'PY'
import json, sys
try:
    data = json.load(open(sys.argv[1]))
    if data.get('errors'):
        raise ValueError()
    zones = data['data']['viewer']['zones']
    if len(zones) != 1:
        raise ValueError()
    events = zones[0]['firewallEventsAdaptive']
    if not isinstance(events, list) or len(events) >= 10000:
        raise ValueError()
    print(json.dumps(events))
except (ValueError, KeyError, TypeError):
    sys.exit('ERROR: incomplete analytics response; shorten interval or resolve API access')
PY
python3 "$(dirname "$0")/sanitize_events.py" "$3" < "$tmp/events"
