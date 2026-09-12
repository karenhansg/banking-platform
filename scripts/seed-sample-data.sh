#!/usr/bin/env bash
#
# Creates sample accounts, transactions and scheduled payments so the API can be explored by hand.
#
#   Local:   make up && scripts/seed-sample-data.sh
#   Remote:  API_BASE_URL=https://... TOKEN_URL=... CLIENT_ID=... CLIENT_SECRET=... scripts/seed-sample-data.sh
#
# Everything goes through the public API, so the same business rules apply as for any other client and the
# database is never touched directly. Each run creates a fresh set of accounts, so it can be run repeatedly.

set -euo pipefail

API_BASE_URL=${API_BASE_URL:-http://localhost:8080}
TOKEN=${TOKEN:-$("$(dirname "$0")/get-token.sh")}

curl_options=(--silent --show-error --fail-with-body --max-time 15
  --header "Authorization: Bearer $TOKEN" --header "Content-Type: application/json")
if [[ "${ALLOW_SELF_SIGNED_TLS:-false}" == "true" ]]; then
  curl_options+=(--insecure)
fi

api() {
  curl "${curl_options[@]}" "$@"
}

open_account() { # owner name, opening deposit
  local account_id
  account_id=$(api --data "{\"ownerName\": \"$1\"}" "$API_BASE_URL/api/v1/accounts" | jq -r .id)
  if [[ "${2:-0.00}" != "0.00" ]]; then
    api --header "Idempotency-Key: seed-$account_id-opening" \
      --data "{\"amount\": $2, \"description\": \"Opening deposit\"}" \
      "$API_BASE_URL/api/v1/accounts/$account_id/deposits" > /dev/null
  fi
  echo "$account_id"
}

show_account() { # owner name, account id
  local balance
  balance=$(api "$API_BASE_URL/api/v1/accounts/$2/balance")
  printf '%-14s %-38s %12s %12s\n' "$1" "$2" \
    "$(jq -r .balance <<< "$balance")" "$(jq -r .remainingDailyWithdrawalLimit <<< "$balance")"
}

show_payment() { # label, payment id
  local payment
  payment=$(api "$API_BASE_URL/api/v1/payments/$2")
  printf '%-14s %-38s %12s %12s  %s\n' "$1" "$2" \
    "$(jq -r .amount <<< "$payment")" "$(jq -r .status <<< "$payment")" "$(jq -r '.expiresAt // .executeAt' <<< "$payment")"
}

ada=$(open_account "Ada Lovelace" 5000.00)
bob=$(open_account "Bob Martin" 1200.00)
carol=$(open_account "Carol Shaw" 0.00)

# Movements on Ada's account: part of today's withdrawal allowance is used, and a deposit that does not restore it.
api --data '{"amount": 250.00, "description": "ATM withdrawal"}' \
  "$API_BASE_URL/api/v1/accounts/$ada/withdrawals" > /dev/null
api --data '{"amount": 75.50, "description": "Refund from merchant"}' \
  "$API_BASE_URL/api/v1/accounts/$ada/deposits" > /dev/null

# A payment whose funds are already held, waiting for Bob to accept it.
held_payment=$(api --header "Idempotency-Key: seed-$ada-invoice-1017" \
  --data "{\"sourceAccountId\": \"$ada\", \"targetAccountId\": \"$bob\", \"amount\": 300.00, \"description\": \"Invoice 1017\"}" \
  "$API_BASE_URL/api/v1/payments" | jq -r .id)

# A payment scheduled for tomorrow morning: no money moves until the scheduler executes it.
execute_at=$(date -u -d '+1 day' +%Y-%m-%dT09:00:00Z 2>/dev/null || date -u -v+1d +%Y-%m-%dT09:00:00Z)
scheduled_payment=$(api --header "Idempotency-Key: seed-$bob-rent" \
  --data "{\"sourceAccountId\": \"$bob\", \"targetAccountId\": \"$carol\", \"amount\": 450.00, \"executeAt\": \"$execute_at\", \"description\": \"Rent\"}" \
  "$API_BASE_URL/api/v1/payments" | jq -r .id)

printf '\nAccounts\n%-14s %-38s %12s %12s\n' "OWNER" "ID" "BALANCE" "REMAINING"
show_account "Ada Lovelace" "$ada"
show_account "Bob Martin" "$bob"
show_account "Carol Shaw" "$carol"

printf '\nPayments\n%-14s %-38s %12s %12s  %s\n' "DESCRIPTION" "ID" "AMOUNT" "STATUS" "EXPIRES / EXECUTES AT"
show_payment "Invoice 1017" "$held_payment"
show_payment "Rent" "$scheduled_payment"

cat <<SUMMARY

REMAINING is what Ada may still withdraw today: 1000.00 minus the 250.00 withdrawal and the 300.00 held
for Invoice 1017. The 75.50 deposit does not restore it.

Next, for example:
  curl -s -X POST "$API_BASE_URL/api/v1/payments/$held_payment/accept" -H "Authorization: Bearer \$TOKEN" | jq .
  curl -s "$API_BASE_URL/api/v1/accounts/$ada/transactions" -H "Authorization: Bearer \$TOKEN" | jq .
SUMMARY
