#!/usr/bin/env bash
# Copies the landing surface from frontend/ into this package, or with --check reports drift.
#
# The prelaunch deployment cannot import frontend/ source: a component resolved from frontend/ loads
# frontend/node_modules, so next-intl and React context end up as two instances and providers
# rendered by this app are invisible to it. The shared files are therefore vendored byte-for-byte.
# Landing-owned files (layout, page, robots, sitemap, the static opengraph-image.png rendered once from
# frontend/app/opengraph-image.tsx because next/og would push the Worker past the free size limit, the signup route and its limiter, proxy,
# i18n request config, and the app/lib/{landingMode,utils,deploymentSurface}.ts stand-ins) are not listed and are never
# overwritten.
set -euo pipefail

here="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
frontend="$(cd "$here/../frontend" && pwd)"

shared=(
  app/components/landing
  app/components/legal
  app/components/ThemeProvider.tsx
  app/components/NotFoundState.tsx
  app/components/PageState.tsx
  app/components/motion/Rise.tsx
  app/lib/launchSignup.ts
  app/lib/motion.ts
  app/lib/requestHost.ts
  app/lib/siteMetadata.ts
  app/privacy/page.tsx
  app/legal/page.tsx
  app/disclosure/page.tsx
  app/tokushoho/page.tsx
  app/not-found.tsx
  app/globals.css
  app/icon.svg
  app/favicon.ico
  app/apple-icon.png
  app/manifest.ts
  components/ui/button.tsx
  components/ui/icon-button.tsx
  components/ui/input.tsx
  components/ui/segmented-control.tsx
  components/ui/tabs.tsx
  components/ui/tooltip.tsx
  lib/utils.ts
  i18n/config.ts
  security-headers.ts
  messages/en/common.json
  messages/en/legal.json
  messages/en/errors.json
  messages/ja/common.json
  messages/ja/legal.json
  messages/ja/errors.json
  public/icons
)

mode="${1:-sync}"
case "$mode" in
  sync|--check) ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

drift=0
for path in "${shared[@]}"; do
  source="$frontend/$path"
  target="$here/$path"
  if [ ! -e "$source" ]; then
    echo "missing in frontend/: $path" >&2
    exit 1
  fi
  if [ "$mode" = "--check" ]; then
    if ! diff -rq "$source" "$target" >/dev/null 2>&1; then
      echo "out of sync: $path"
      drift=1
    fi
  else
    mkdir -p "$(dirname "$target")"
    if [ -d "$source" ]; then
      rm -rf "$target"
      cp -R "$source" "$target"
    else
      cp "$source" "$target"
    fi
  fi
done

if [ "$mode" = "--check" ] && [ "$drift" -ne 0 ]; then
  echo "landing/ has drifted from frontend/. Run landing/scripts/sync-from-frontend.sh and commit the result." >&2
  exit 1
fi
[ "$mode" = "--check" ] && echo "landing/ matches frontend/ for all ${#shared[@]} shared paths."
exit 0
