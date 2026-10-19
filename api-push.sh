#!/bin/sh
# Push files to GitHub via the Contents API.
#
# Why not the Git Data API: this environment can only reach api.github.com
# (github.com is SNI-blocked), and the trees endpoint additionally rejects
# paths under .github/ with a 404. The Contents API accepts them.

set -e

ROOT="/data/user/0/com.ocforandroid.web/files/files/usr/tmp/opencode/ColorReader"
TMP="/data/user/0/com.ocforandroid.web/files/files/usr/tmp"
TOKEN=$(cat "$TMP/.ghtok")
REPO="yanggggg666/ColorReader"
API="https://api.github.com"
BRANCH="main"

cd "$ROOT"

api() {
  method=$1; path=$2; data=${3:-}
  attempt=0
  while [ $attempt -lt 6 ]; do
    attempt=$((attempt + 1))
    if [ -n "$data" ]; then
      out=$(curl -sS --connect-timeout 20 --max-time 240 \
        -X "$method" -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" -H "Content-Type: application/json" \
        --data-binary "$data" "$API$path" 2>/dev/null)
    else
      out=$(curl -sS --connect-timeout 20 --max-time 240 \
        -X "$method" -H "Authorization: Bearer $TOKEN" \
        -H "Accept: application/vnd.github+json" "$API$path" 2>/dev/null)
    fi
    [ -n "$out" ] && { printf '%s' "$out"; return 0; }
    sleep $((attempt * 2))
  done
  return 1
}

sha_of() {
  grep -o '"sha": *"[a-f0-9]\{40\}"' | head -1 | sed 's/.*"\([a-f0-9]\{40\}\)".*/\1/'
}

log() { printf '    %s\n' "$1"; }

# ---- ensure the repo has a main branch -------------------------------
CURRENT=$(api GET "/repos/$REPO/git/ref/heads/$BRANCH" 2>/dev/null | sha_of)
if [ -z "$CURRENT" ]; then
  SEED=$(api PUT "/repos/$REPO/contents/.seed" \
    '{"message":"init","content":"aW5pdA==","branch":"main"}')
  CURRENT=$(printf '%s' "$SEED" | sha_of)
  [ -n "$CURRENT" ] || { echo "!! repo init failed"; printf '%s' "$SEED" | head -12; exit 1; }
  log "initialised repo, base commit $CURRENT"
else
  log "base commit $CURRENT"
fi

# ---- upload every tracked file ---------------------------------------
echo "==> uploading files via Contents API..."
git ls-tree -r --full-tree HEAD | while IFS="$(printf '\t')" read -r meta path; do
  [ -z "$path" ] && continue

  b64=$(base64 < "$path" | tr -d '\n')

  # Fetch the current blob sha so we can PUT an update instead of a create.
  EXIST=$(api GET "/repos/$REPO/contents/$path?ref=$BRANCH" 2>/dev/null | sha_of)

  if [ -n "$EXIST" ]; then
    PAYLOAD="{\"message\":\"sync\",\"content\":\"$b64\",\"sha\":\"$EXIST\",\"branch\":\"$BRANCH\"}"
    RESULT=$(api PUT "/repos/$REPO/contents/$path" "$PAYLOAD")
  else
    PAYLOAD="{\"message\":\"sync\",\"content\":\"$b64\",\"branch\":\"$BRANCH\"}"
    RESULT=$(api PUT "/repos/$REPO/contents/$path" "$PAYLOAD")
  fi

  OK=$(printf '%s' "$RESULT" | sha_of)
  if [ -z "$OK" ]; then
    echo "!! failed: $path"
    printf '%s' "$RESULT" | head -10
    continue
  fi
  printf '    %s\n' "$path"
done

# ---- remove the seed file --------------------------------------------
SEED_SHA=$(api GET "/repos/$REPO/contents/.seed" 2>/dev/null | sha_of)
if [ -n "$SEED_SHA" ]; then
  log "removing .seed"
  api DELETE "/repos/$REPO/contents/.seed" \
    "{\"message\":\"remove seed\",\"sha\":\"$SEED_SHA\",\"branch\":\"$BRANCH\"}" > /dev/null
fi

echo ""
echo "==> done: https://github.com/$REPO"
echo "    build:   https://github.com/$REPO/actions"