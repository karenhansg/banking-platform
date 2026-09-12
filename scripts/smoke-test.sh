#!/usr/bin/env bash
#
# Checks a deployed environment end to end through the load balancer:
#   - the API answers over HTTPS and rejects a request without a token
#   - with client credentials (optional): a client can open an account, deposit, withdraw and read the balance
#
#   API_BASE_URL=https://... [ALLOW_SELF_SIGNED_TLS=true] \
#   [TOKEN_URL=... CLIENT_ID=... CLIENT_SECRET=...] scripts/smoke-test.sh

set -euo pipefail

: "${API_BASE_URL:?API_BASE_URL must be set}"

curl_options=(--silent --show-error --max-time 15 --retry 3 --retry-all-errors)
if [[ "${ALLOW_SELF_SIGNED_TLS:-false}" == "true" ]]; then
  curl_options+=(--insecure)
fi

fail() {
  printf 'SMOKE TEST FAILED: %s\n' "$*" >&2
  exit 1
}

status=$(curl "${curl_options[@]}" --output /dev/null --write-out '%{http_code}' \
  "$API_BASE_URL/api/v1/accounts/00000000-0000-0000-0000-000000000000/balance")
[[ "$status" == "401" ]] || fail "expected 401 for a request without a token, got $status"
echo "OK  request without a token is rejected (401)"

if [[ -z "${TOKEN_URL:-}" || -z "${CLIENT_ID:-}" || -z "${CLIENT_SECRET:-}" ]]; then
  echo "SKIP authenticated checks (TOKEN_URL, CLIENT_ID and CLIENT_SECRET not set)"
  exit 0
fi

token=$(curl "${curl_options[@]}" --fail --user "$CLIENT_ID:$CLIENT_SECRET" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=banking-api/accounts.read banking-api/accounts.write" \
  "$TOKEN_URL" | jq -r .access_token)
[[ -n "$token" && "$token" != "null" ]] || fail "could not obtain an access token"

api() {
  curl "${curl_options[@]}" --fail-with-body --header "Authorization: Bearer $token" \
    --header 'Content-Type: application/json' "$@"
}

account_id=$(api --data '{"ownerName": "Smoke Test"}' "$API_BASE_URL/api/v1/accounts" | jq -r .id)
api --data '{"amount": 100.00}' "$API_BASE_URL/api/v1/accounts/$account_id/deposits" > /dev/null
api --data '{"amount": 40.00}' "$API_BASE_URL/api/v1/accounts/$account_id/withdrawals" > /dev/null
balance=$(api "$API_BASE_URL/api/v1/accounts/$account_id/balance" | jq -r .balance)
[[ "$balance" == "60" || "$balance" == "60.0" || "$balance" == "60.00" ]] || fail "expected a balance of 60.00, got $balance"
echo "OK  account $account_id: deposit, withdrawal and balance"
