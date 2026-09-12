package com.example.banking.support;

import com.example.banking.config.Scopes;
import java.util.Arrays;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;

/** Authenticates MockMvc requests as a client holding the given scopes, without a real token issuer. */
public final class TestJwt {

    private TestJwt() {
    }

    public static JwtRequestPostProcessor withScopes(String... scopes) {
        return SecurityMockMvcRequestPostProcessors.jwt()
                .authorities(Arrays.stream(scopes).map(SimpleGrantedAuthority::new).toArray(GrantedAuthority[]::new));
    }

    public static JwtRequestPostProcessor fullAccess() {
        return withScopes(Scopes.ACCOUNTS_READ, Scopes.ACCOUNTS_WRITE, Scopes.PAYMENTS_READ, Scopes.PAYMENTS_WRITE);
    }
}
