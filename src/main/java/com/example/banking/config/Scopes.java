package com.example.banking.config;

/**
 * OAuth2 scopes accepted by the API, expressed as Spring Security authorities. The {@code banking-api/} prefix is the
 * Cognito resource server identifier defined in {@code infra/modules/auth}.
 */
public final class Scopes {

    public static final String ACCOUNTS_READ = "SCOPE_banking-api/accounts.read";
    public static final String ACCOUNTS_WRITE = "SCOPE_banking-api/accounts.write";
    public static final String PAYMENTS_READ = "SCOPE_banking-api/payments.read";
    public static final String PAYMENTS_WRITE = "SCOPE_banking-api/payments.write";

    private Scopes() {
    }
}
