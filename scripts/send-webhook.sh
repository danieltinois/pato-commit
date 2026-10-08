#!/usr/bin/env bash
# Sends a webhook delivery signed exactly the way GitHub signs one, for testing
# the intake without GitHub. Swagger UI cannot do this: the signature is an
# HMAC over the exact request bytes, computed with the webhook secret.
#
# Usage:
#   PATO_COMMIT_GITHUB_WEBHOOK_SECRET=... scripts/send-webhook.sh <event> [payload.json] [url]
#
#   event         value for X-GitHub-Event, e.g. ping or installation
#   payload.json  file with the JSON body; defaults to a ping payload
#   url           defaults to the webhook-only port, http://127.0.0.1:8090/api/webhooks/github
#
# Example:
#   scripts/send-webhook.sh ping
set -euo pipefail

event="${1:?usage: $0 <event> [payload.json] [url]}"
payload_file="${2:-}"
url="${3:-http://127.0.0.1:8090/api/webhooks/github}"
secret="${PATO_COMMIT_GITHUB_WEBHOOK_SECRET:?set PATO_COMMIT_GITHUB_WEBHOOK_SECRET to the webhook secret of the API}"

body_file="$(mktemp)"
trap 'rm -f "$body_file"' EXIT
if [[ -n "$payload_file" ]]; then
  cp "$payload_file" "$body_file"
else
  printf '%s' '{"zen":"Quack quack.","hook_id":1}' > "$body_file"
fi

# Signed from the file, and sent from the same file with --data-binary, so the
# bytes signed are the bytes sent. --data would strip newlines and break that.
signature="sha256=$(openssl dgst -sha256 -hmac "$secret" "$body_file" | awk '{print $NF}')"
delivery_id="$(uuidgen | tr '[:upper:]' '[:lower:]')"

echo "POST $url  event=$event  delivery=$delivery_id"
curl --silent --show-error --output /dev/null --write-out 'HTTP %{http_code}\n' \
  --request POST "$url" \
  --header "Content-Type: application/json" \
  --header "X-GitHub-Event: $event" \
  --header "X-GitHub-Delivery: $delivery_id" \
  --header "X-Hub-Signature-256: $signature" \
  --data-binary "@$body_file"
