#!/usr/bin/env bash
# UI Lab — log the VRChat ALT in (the password is added by the environment's credential proxy
# for api.vrchat.cloud; it is never on this machine). Session cookies land in ui-shots/.vrc/.
#
#   tools/ui-lab/vrc-login.sh            # start / check the login
#   tools/ui-lab/vrc-login.sh 123456     # finish it with the 2FA code VRChat sent
#   tools/ui-lab/vrc-login.sh status     # is the saved session still valid?
set -euo pipefail
ROOT=$(cd "$(dirname "$0")/../.." && pwd)
DIR="$ROOT/ui-shots/.vrc"; mkdir -p "$DIR"; chmod 700 "$DIR"
API=https://api.vrchat.cloud/api/1
UA="VRC-A-Companion/1.0 (Android; companion app)"
JAR="$DIR/cookies.txt"; SESSION="$DIR/session.json"
touch "$JAR"; chmod 600 "$JAR"

cookie() { awk -v n="$1" '$6==n {v=$7} END {if (v!="") print n"="v}' "$JAR"; }

save_session() {   # $1 = /auth/user JSON
  python3 - "$1" "$SESSION" "$(cookie auth)" "$(cookie twoFactorAuth)" <<'PY'
import json, sys, os
u = json.load(open(sys.argv[1]))
out = {"auth": sys.argv[3], "twoFactorAuth": sys.argv[4], "userId": u.get("id", ""),
       "displayName": u.get("displayName", "")}
open(sys.argv[2], "w").write(json.dumps(out)); os.chmod(sys.argv[2], 0o600)
print("logged in as", out["displayName"], out["userId"])
PY
}

case "${1:-}" in
  status)
    [[ -f "$SESSION" ]] || { echo "no session"; exit 1; }
    code=$(curl -s -o /dev/null -w "%{http_code}" -A "$UA" -b "$JAR" "$API/auth")
    echo "session check: HTTP $code"; exit 0 ;;
  "")
    code=$(curl -s -o "$DIR/r.json" -w "%{http_code}" -A "$UA" -b "$JAR" -c "$JAR" "$API/auth/user")
    if [[ "$code" == 200 ]] && python3 -c "import json,sys; sys.exit(0 if json.load(open('$DIR/r.json')).get('id') else 1)"; then
      save_session "$DIR/r.json"
    elif [[ "$code" == 200 ]]; then
      echo "VRChat wants a 2FA code: $(python3 -c "import json; print(json.load(open('$DIR/r.json'))['requiresTwoFactorAuth'])")"
      echo "run: tools/ui-lab/vrc-login.sh <code>"
    else
      echo "HTTP $code: $(head -c 300 "$DIR/r.json")"; exit 1
    fi ;;
  *)
    CODE=$1
    for kind in emailotp totp otp; do
      code=$(curl -s -o "$DIR/v.json" -w "%{http_code}" -A "$UA" -b "$JAR" -c "$JAR" \
        -H 'Content-Type: application/json' -d "{\"code\":\"$CODE\"}" "$API/auth/twofactorauth/$kind/verify")
      [[ "$code" == 200 ]] && break
    done
    [[ "$code" == 200 ]] || { echo "verify failed: HTTP $code $(head -c 300 "$DIR/v.json")"; exit 1; }
    code=$(curl -s -o "$DIR/r.json" -w "%{http_code}" -A "$UA" -b "$JAR" -c "$JAR" "$API/auth/user")
    [[ "$code" == 200 ]] || { echo "user fetch failed: HTTP $code"; exit 1; }
    save_session "$DIR/r.json" ;;
esac
