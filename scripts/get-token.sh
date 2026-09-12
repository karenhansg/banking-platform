#!/usr/bin/env bash
#
# Prints an access token obtained with the OAuth2 client credentials grant.
#
#   Local (docker compose mock server):
#     scripts/get-token.sh
#     SCOPE="banking-api/accounts.read" scripts/get-token.sh      # a read-only token
#
#   AWS (Cognito; token endpoint and client ID are Terraform outputs, see docs/DEPLOYMENT.md):
#     TOKEN_URL=https://<domain>.auth.<region>.amazoncognito.com/oauth2/token \
#     CLIENT_ID=<id> CLIENT_SECRET=<secret> scripts/get-token.sh

set -euo pipefail

TOKEN_URL=${TOKEN_URL:-http://localhost:8090/banking/token}
CLIENT_ID=${CLIENT_ID:-local-client}
CLIENT_SECRET=${CLIENT_SECRET:-local-secret}
SCOPE=${SCOPE:-banking-api/accounts.read banking-api/accounts.write banking-api/payments.read banking-api/payments.write}

curl --silent --show-error --fail \
  --user "$CLIENT_ID:$CLIENT_SECRET" \
  --data-urlencode grant_type=client_credentials \
  --data-urlencode "scope=$SCOPE" \
  "$TOKEN_URL" | jq -r .access_token
