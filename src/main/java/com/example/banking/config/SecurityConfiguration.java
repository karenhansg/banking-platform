package com.example.banking.config;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Every API call needs a valid JWT access token (issued by Cognito in AWS) carrying the scope for the operation.
 * Anything not explicitly allowed is denied.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnWebApplication
public class SecurityConfiguration {

    @Bean
    SecurityFilterChain apiSecurityFilterChain(HttpSecurity http) throws Exception {
        http
                // Stateless bearer-token API: there are no session cookies for CSRF to abuse.
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(requests -> requests
                        .requestMatchers("/error", "/actuator/health/**", "/actuator/info").permitAll()
                        // Only served when springdoc is enabled (disabled in AWS).
                        .requestMatchers("/v3/api-docs/**", "/swagger-ui.html", "/swagger-ui/**").permitAll()
                        .requestMatchers(HttpMethod.GET, "/api/v1/accounts/**").hasAuthority(Scopes.ACCOUNTS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/accounts/**").hasAuthority(Scopes.ACCOUNTS_WRITE)
                        .requestMatchers(HttpMethod.GET, "/api/v1/payments/**").hasAuthority(Scopes.PAYMENTS_READ)
                        .requestMatchers(HttpMethod.POST, "/api/v1/payments/**").hasAuthority(Scopes.PAYMENTS_WRITE)
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
