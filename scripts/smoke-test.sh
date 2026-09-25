#!/usr/bin/env bash
# Boots the packaged service on a free port with a throwaway database and
# checks the HTTP contract end to end with curl. Exits non-zero on any mismatch.
set -euo pipefail
cd "$(dirname "$0")/.."

port="${PORT:-18080}"
base="http://localhost:${port}"
data_dir="$(mktemp -d)"
mvn -B -q -DskipTests package
java -jar target/agentic-sdlc-1.0.0.jar --server.port="$port" \
     --spring.datasource.url="jdbc:h2:file:${data_dir}/shortener" \
     --shortener.base-url="$base" > "${data_dir}/service.log" 2>&1 &
pid=$!
trap 'kill $pid 2>/dev/null || true; rm -rf "$data_dir"' EXIT

for _ in $(seq 1 60); do
    curl -fs "$base/health" > /dev/null 2>&1 && break
    sleep 1
done

fail=0
expect() { # expect <label> <expected-status> <actual-status>
    if [[ "$2" == "$3" ]]; then echo "PASS $1 -> $3"; else echo "FAIL $1 -> expected $2, got $3"; fail=1; fi
}
json() { curl -s -o "${data_dir}/body" -w '%{http_code}' "$@"; }

status=$(json -X POST "$base/shorten" -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-1' \
         -d '{"url":"https://example.com/docs"}'); body=$(cat "${data_dir}/body")
expect "POST /shorten (new link)" 201 "$status"; echo "     $body"
code=$(sed -E 's/.*"code":"([^"]+)".*/\1/' <<< "$body")

status=$(json -X POST "$base/shorten" -H 'Content-Type: application/json' -H 'Idempotency-Key: smoke-1' \
         -d '{"url":"https://example.com/docs"}'); replay=$(cat "${data_dir}/body")
expect "POST /shorten (idempotent replay)" 200 "$status"; echo "     $replay"
[[ "$replay" == *"\"code\":\"$code\""* ]] && echo "PASS replay returned the same code ($code)" || { echo "FAIL replay code differs"; fail=1; }

status=$(curl -s -o /dev/null -w '%{http_code} %{redirect_url}' -H 'Referer: https://news.example.org/post' "$base/$code")
expect "GET /$code (redirect)" "302 https://example.com/docs" "$status"

status=$(json "$base/stats/$code"); stats=$(cat "${data_dir}/body")
expect "GET /stats/$code" 200 "$status"; echo "     $stats"
[[ "$stats" == *'"totalClicks":1'* ]] && echo "PASS stats show the click" || { echo "FAIL click not counted"; fail=1; }

status=$(json -X POST "$base/shorten" -H 'Content-Type: application/json' -d '{"url":"http://169.254.169.254/latest/meta-data"}')
expect "POST /shorten (SSRF metadata IP)" 400 "$status"; echo "     $(cat "${data_dir}/body")"

status=$(json "$base/doesNotExist1")
expect "GET /doesNotExist1 (unknown code)" 404 "$status"

status=$(json "$base/health")
expect "GET /health" 200 "$status"; echo "     $(cat "${data_dir}/body")"

# The JVM's own "Picked up JAVA_TOOL_OPTIONS" banner (set by some CI/sandbox proxies) is not service output.
if grep -v '^Picked up JAVA_TOOL_OPTIONS' "${data_dir}/service.log" | grep -Eq '\b(127\.0\.0\.1|0:0:0:0:0:0:0:1)\b'; then
    echo "FAIL service log contains a raw client IP"; fail=1
else
    echo "PASS service log contains no raw client IP"
fi
exit $fail
