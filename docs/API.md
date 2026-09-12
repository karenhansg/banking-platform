# API reference

- Base URL: `http://localhost:8080` locally; `terraform output api_base_url` in AWS.
- All endpoints are under `/api/v1`.
- Interactive documentation is available locally (it is disabled in AWS): Swagger UI at `/swagger-ui.html`, OpenAPI document at `/v3/api-docs`.
- A [Postman collection](../postman) covers every request below, including the rejection cases, and can be run end to end with `make postman`.

## Contents

- [Authentication](#authentication)
- [Conventions](#conventions)
- [Accounts](#accounts)
- [Scheduled payments](#scheduled-payments)
- [Errors](#errors)

## Authentication

Clients obtain an access token with the OAuth2 client credentials grant, then send it as a bearer token:

```bash
TOKEN=$(scripts/get-token.sh)
curl -H "Authorization: Bearer $TOKEN" http://localhost:8080/api/v1/accounts/<account-id>/balance
```

Each operation requires a scope:

| Scope | Allows |
|---|---|
| `banking-api/accounts.read` | `GET /accounts/…` |
| `banking-api/accounts.write` | `POST /accounts`, deposits and withdrawals |
| `banking-api/payments.read` | `GET /payments/…` |
| `banking-api/payments.write` | `POST /payments`, accept and cancel |

A missing, invalid or expired token gets `401`; a token without the required scope gets `403`. Both responses have an empty body and a `WWW-Authenticate` header.

## Conventions

- **Format.** Requests and responses are JSON. Identifiers are UUIDs. Timestamps are ISO-8601 instants in UTC.
- **Money.** Amounts are JSON numbers with at most two decimal places, from `0.01` to `9999999999999.99`. An amount with more decimals is rejected, not rounded.
- **Idempotency.** Deposits, withdrawals and payment creation accept an optional `Idempotency-Key` header of 1–64 characters from `A–Z a–z 0–9 _ . : -`. Retrying with the same key returns the original result without moving money again. Reusing a key for a different request returns `409`.
- **Request ID.** Send `X-Request-Id` to correlate a request with the logs; otherwise one is generated. It is returned in the `X-Request-Id` response header and in error bodies.
- **Lists.** List endpoints take `page` (from 0, default 0) and `size` (1–100, default 20), return newest first, and respond with:

  ```json
  { "content": [ ... ], "page": 0, "size": 20, "totalElements": 42, "totalPages": 3 }
  ```

## Accounts

### Open an account

`POST /api/v1/accounts` · `accounts.write`

```json
{ "ownerName": "Ada Lovelace" }
```

`201 Created`, with `Location: /api/v1/accounts/{accountId}`:

```json
{
  "id": "d396a162-af4b-4a19-9439-e835728eb7eb",
  "ownerName": "Ada Lovelace",
  "balance": 0.00,
  "createdAt": "2026-09-11T17:25:30.801818Z"
}
```

`ownerName` is required and at most 100 characters.

### Get an account

`GET /api/v1/accounts/{accountId}` · `accounts.read`

`200 OK` with the same body as above, or `404 ACCOUNT_NOT_FOUND`.

### Get the balance

`GET /api/v1/accounts/{accountId}/balance` · `accounts.read`

`200 OK`:

```json
{
  "accountId": "d396a162-af4b-4a19-9439-e835728eb7eb",
  "balance": 1300.00,
  "dailyWithdrawalLimit": 1000.00,
  "remainingDailyWithdrawalLimit": 300.00,
  "asOf": "2026-09-11T17:25:31.041830Z"
}
```

`remainingDailyWithdrawalLimit` is how much more can be withdrawn, or paid out by scheduled payments, before the limit resets at midnight in the bank's time zone.

### Deposit

`POST /api/v1/accounts/{accountId}/deposits` · `accounts.write` · optional `Idempotency-Key`

```json
{ "amount": 2000.00, "description": "Salary" }
```

`description` is optional, at most 140 characters. Response `201 Created`:

```json
{
  "id": "36bddcc5-b599-42b2-a2c5-60ea3604c5f2",
  "accountId": "d396a162-af4b-4a19-9439-e835728eb7eb",
  "type": "DEPOSIT",
  "amount": 2000.00,
  "balanceAfter": 2000.00,
  "description": "Salary",
  "paymentId": null,
  "createdAt": "2026-09-11T17:25:30.912201Z"
}
```

### Withdraw

`POST /api/v1/accounts/{accountId}/withdrawals` · `accounts.write` · optional `Idempotency-Key`

Same request and response shape as a deposit, with `"type": "WITHDRAWAL"`.

A withdrawal is refused if it exceeds the balance (`422 INSUFFICIENT_FUNDS`), or if it would take the day's withdrawals and scheduled payments above the daily limit of 1,000.00. For example, after withdrawing 500.00 and then 200.00, asking for 300.01 returns `422`:

```json
{
  "title": "Daily withdrawal limit exceeded",
  "status": 422,
  "detail": "Requested amount 300.01 exceeds your remaining daily withdrawal limit of 300.00 (daily limit: 1000.00). Please contact the bank for assistance.",
  "instance": "/api/v1/accounts/d396a162-af4b-4a19-9439-e835728eb7eb/withdrawals",
  "code": "DAILY_WITHDRAWAL_LIMIT_EXCEEDED",
  "dailyLimit": 1000.00,
  "remainingToday": 300.00,
  "requestedAmount": 300.01,
  "requestId": "167b6a98-ac04-481b-83fa-e95f728328ec"
}
```

### List transactions

`GET /api/v1/accounts/{accountId}/transactions?page=0&size=20` · `accounts.read`

A page of ledger entries, newest first. Entry types:

| Type | Effect | Counts towards the daily limit |
|---|---|---|
| `DEPOSIT` | Credit | No |
| `WITHDRAWAL` | Debit | Yes |
| `PAYMENT_HOLD` | Debit: funds taken for a scheduled payment | Yes |
| `PAYMENT_REFUND` | Credit: held funds returned after the payment expired | No |
| `PAYMENT_RECEIVED` | Credit: an accepted payment from another account | No |

## Scheduled payments

A payment moves money from a source account to a target account. When it executes, the funds are taken from the source account (counting towards that day's withdrawal limit) and held until the target accepts. If the target does not accept within the acceptance window, the funds go back to the source account.

```
SCHEDULED ──execute──▶ PENDING_ACCEPTANCE ──accept──▶ COMPLETED
    │                         │
    ├──cancel──▶ CANCELLED    └──expire──▶ EXPIRED (funds returned)
    └──limit or funds──▶ FAILED
```

| Status | Meaning |
|---|---|
| `SCHEDULED` | Waiting for `executeAt`; no money has moved. |
| `PENDING_ACCEPTANCE` | Funds held from the source; waiting for the target to accept until `expiresAt`. |
| `COMPLETED` | Accepted; the target was credited. |
| `EXPIRED` | Not accepted in time; the funds were returned to the source. |
| `CANCELLED` | Cancelled before executing. |
| `FAILED` | Could not execute: the daily limit or the balance did not allow it (`failureReason`). |

### Schedule a payment

`POST /api/v1/payments` · `payments.write` · optional `Idempotency-Key`

```json
{
  "sourceAccountId": "d396a162-af4b-4a19-9439-e835728eb7eb",
  "targetAccountId": "bc937e0e-6235-4a99-a368-1ff53f9f245a",
  "amount": 250.00,
  "executeAt": "2026-09-12T01:00:00Z",
  "description": "Rent"
}
```

| Field | Rules |
|---|---|
| `sourceAccountId`, `targetAccountId` | Required; must be different, existing accounts |
| `amount` | Required; see [money](#conventions) |
| `executeAt` | Optional; must be in the future. Omit it to pay immediately. |
| `description` | Optional; at most 140 characters |

- **Without `executeAt`**, the funds are held immediately. The response is `201` with status `PENDING_ACCEPTANCE`, or `422` (`DAILY_WITHDRAWAL_LIMIT_EXCEEDED` or `INSUFFICIENT_FUNDS`), in which case no payment is created.
- **With `executeAt`**, the response is `201` with status `SCHEDULED`. The daily limit and the balance are checked when the payment executes; an amount above the daily limit is rejected immediately, since it could never execute.

`201 Created`, with `Location: /api/v1/payments/{paymentId}`:

```json
{
  "id": "68966fc1-41c5-4141-8f5d-ffd739d34742",
  "sourceAccountId": "d396a162-af4b-4a19-9439-e835728eb7eb",
  "targetAccountId": "bc937e0e-6235-4a99-a368-1ff53f9f245a",
  "amount": 250.00,
  "description": null,
  "status": "PENDING_ACCEPTANCE",
  "executeAt": "2026-09-11T17:25:31.127338Z",
  "heldAt": "2026-09-11T17:25:31.127338Z",
  "expiresAt": "2026-09-14T17:25:31.127338Z",
  "resolvedAt": null,
  "failureReason": null,
  "createdAt": "2026-09-11T17:25:31.127338Z"
}
```

### Get a payment

`GET /api/v1/payments/{paymentId}` · `payments.read`

`200 OK` with the payment, or `404 PAYMENT_NOT_FOUND`.

### List an account's payments

`GET /api/v1/payments?accountId={accountId}&status=PENDING_ACCEPTANCE&page=0&size=20` · `payments.read`

Payments the account sent or received, newest first. `status` is optional. A target account uses this to find payments waiting for its acceptance.

### Accept a payment

`POST /api/v1/payments/{paymentId}/accept` · `payments.write`

- `200 OK` with status `COMPLETED`: the target account has been credited. Accepting a completed payment again returns it unchanged.
- `409 PAYMENT_EXPIRED` if the acceptance window has passed. The held funds are returned to the source account if that has not happened already.
- `409 INVALID_PAYMENT_STATE` if the payment has not executed yet, or was cancelled or failed.

### Cancel a payment

`POST /api/v1/payments/{paymentId}/cancel` · `payments.write`

- `200 OK` with status `CANCELLED`, for a payment that has not executed. Cancelling again returns it unchanged.
- `409 INVALID_PAYMENT_STATE` once funds are held or the payment has finished. The response's `paymentStatus` gives the current status.

## Errors

Errors are [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457) problem responses with content type `application/problem+json`. Clients should use `code` rather than parsing `detail`:

| HTTP status | `code` | When | Extra fields |
|---|---|---|---|
| 400 | `VALIDATION_FAILED` | A field, parameter or header is invalid | `errors`: list of `{field, message}` |
| 400 | `MALFORMED_REQUEST` | The body is missing or is not valid JSON | |
| 400 | `INVALID_PAYMENT_REQUEST` | Source and target are the same, or `executeAt` is not in the future | |
| 404 | `ACCOUNT_NOT_FOUND`, `PAYMENT_NOT_FOUND` | No such account or payment | |
| 409 | `IDEMPOTENCY_KEY_REUSED` | The key was used for a different request | |
| 409 | `INVALID_PAYMENT_STATE` | The operation is not allowed in the payment's current status | `paymentStatus` |
| 409 | `PAYMENT_EXPIRED` | Accepting after the acceptance window | `expiredAt` |
| 409 | `CONCURRENT_MODIFICATION` | The account was busy for too long; retry | |
| 422 | `INSUFFICIENT_FUNDS` | The amount exceeds the balance | `availableBalance`, `requestedAmount` |
| 422 | `DAILY_WITHDRAWAL_LIMIT_EXCEEDED` | The amount exceeds what remains of today's limit; the customer is asked to contact the bank | `dailyLimit`, `remainingToday`, `requestedAmount` |
| 500 | `INTERNAL_ERROR` | An unexpected error; quote the `requestId` | |

Every problem response also includes `title`, `status`, `detail`, `instance` and `requestId`.

A validation error looks like this:

```json
{
  "title": "Request validation failed",
  "status": 400,
  "detail": "One or more fields are invalid",
  "instance": "/api/v1/accounts/d396a162-af4b-4a19-9439-e835728eb7eb/withdrawals",
  "code": "VALIDATION_FAILED",
  "errors": [ { "field": "amount", "message": "numeric value out of bounds (<13 digits>.<2 digits> expected)" } ],
  "requestId": "5c1f0a52-3d0e-4c35-9d8e-1f7d7f3f2a10"
}
```
