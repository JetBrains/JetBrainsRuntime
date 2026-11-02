#!/bin/bash
# Publishes build/skia-eval-out/dist to GitHub Pages as the single commit of manu-unter/text-rendering-catalog (branch main):
# the branch always holds exactly the current catalog, older versions are not kept (user's decision,
# 2026-10-27; ~400 MB of binaries per release). Usage: publish.sh [message]   — run make-dist.py first.
set -eu
HERE="$(cd "$(dirname "$0")" && pwd)"
source "$HERE/../env.sh"
DIST="$OUT_ROOT/dist"; REPO="git@github.com:manu-unter/text-rendering-catalog.git"; SLUG="manu-unter/text-rendering-catalog"
[ -f "$DIST/index.html" ] || { echo "no dist/: run make-dist.py first" >&2; exit 1; }
MSG="${1:-Catalog $(date +%F)}"
cd "$DIST"
rm -rf .git
git init -q -b main
git add -A
git -c user.name="$(git -C "$HERE" config user.name)" -c user.email="$(git -C "$HERE" config user.email)" commit -q -m "$MSG"
git remote add origin "$REPO"
git push --force -q origin main
rm -rf .git
# Pages: the Actions deployment when dist carries the workflow (make-dist.py --workflow), else the legacy
# builder from branch main, root; idempotent (POST fails once Pages exists, PUT then updates)
TYPE=legacy; [ -f "$DIST/.github/workflows/pages.yml" ] && TYPE=workflow
gh api -X POST "repos/$SLUG/pages" -f build_type=$TYPE -f 'source[branch]=main' -f 'source[path]=/' >/dev/null 2>&1 \
  || gh api -X PUT "repos/$SLUG/pages" -f build_type=$TYPE -f 'source[branch]=main' -f 'source[path]=/' >/dev/null
echo "pushed: https://github.com/$SLUG — site: $(gh api "repos/$SLUG/pages" -q .html_url) (build takes a few minutes)"
