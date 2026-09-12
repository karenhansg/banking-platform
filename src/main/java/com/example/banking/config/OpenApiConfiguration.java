package com.example.banking.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import org.springframework.context.annotation.Configuration;

/** OpenAPI document served at {@code /v3/api-docs} and Swagger UI at {@code /swagger-ui.html} (local only). */
@Configuration(proxyBeanMethods = false)
@OpenAPIDefinition(
        info = @Info(
                title = "Banking API",
                version = "v1",
                description = "Account balances, deposits, withdrawals with a daily limit, and scheduled payments."),
        security = @SecurityRequirement(name = OpenApiConfiguration.BEARER_JWT))
@SecurityScheme(
        name = OpenApiConfiguration.BEARER_JWT,
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT")
class OpenApiConfiguration {

    static final String BEARER_JWT = "bearer-jwt";
}
