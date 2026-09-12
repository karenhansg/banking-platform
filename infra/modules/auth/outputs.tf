output "user_pool_id" {
  value = aws_cognito_user_pool.this.id
}

output "issuer_uri" {
  description = "Expected iss claim of access tokens (application setting JWT_ISSUER_URI)."
  value       = local.issuer_uri
}

output "jwk_set_uri" {
  description = "Public keys that verify token signatures (application setting JWT_JWK_SET_URI)."
  value       = "${local.issuer_uri}/.well-known/jwks.json"
}

output "token_endpoint" {
  description = "OAuth2 token endpoint for the client credentials grant."
  value       = "https://${aws_cognito_user_pool_domain.this.domain}.auth.${data.aws_region.current.region}.amazoncognito.com/oauth2/token"
}

output "client_ids" {
  description = "Client IDs by client name. Secrets are not output; read them with aws cognito-idp describe-user-pool-client."
  value       = { for name, client in aws_cognito_user_pool_client.this : name => client.id }
}
