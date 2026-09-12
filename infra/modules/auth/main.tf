# Amazon Cognito issues the JWT access tokens the API validates. Client applications (for example a mobile
# back end or an operations console) authenticate with the OAuth2 client credentials grant and receive only the
# scopes they are registered for.

data "aws_region" "current" {}

locals {
  resource_server_identifier = "banking-api"
  issuer_uri                 = "https://cognito-idp.${data.aws_region.current.region}.amazonaws.com/${aws_cognito_user_pool.this.id}"
}

resource "random_id" "domain_suffix" {
  byte_length = 4
}

resource "aws_cognito_user_pool" "this" {
  name                = var.name
  deletion_protection = var.deletion_protection ? "ACTIVE" : "INACTIVE"

  # There is no self-service sign-up: identities are provisioned by administrators only.
  admin_create_user_config {
    allow_admin_create_user_only = true
  }

  password_policy {
    minimum_length                   = 14
    require_lowercase                = true
    require_uppercase                = true
    require_numbers                  = true
    require_symbols                  = true
    temporary_password_validity_days = 1
  }

  mfa_configuration = "ON"

  software_token_mfa_configuration {
    enabled = true
  }
}

# Hosts the token endpoint: https://<domain>.auth.<region>.amazoncognito.com/oauth2/token
resource "aws_cognito_user_pool_domain" "this" {
  domain       = "${var.name}-${random_id.domain_suffix.hex}"
  user_pool_id = aws_cognito_user_pool.this.id
}

resource "aws_cognito_resource_server" "api" {
  identifier   = local.resource_server_identifier
  name         = "Banking API"
  user_pool_id = aws_cognito_user_pool.this.id

  dynamic "scope" {
    for_each = var.scopes

    content {
      scope_name        = scope.key
      scope_description = scope.value
    }
  }
}

resource "aws_cognito_user_pool_client" "this" {
  for_each = var.clients

  name         = "${var.name}-${each.key}"
  user_pool_id = aws_cognito_user_pool.this.id

  generate_secret                      = true
  allowed_oauth_flows_user_pool_client = true
  allowed_oauth_flows                  = ["client_credentials"]
  allowed_oauth_scopes                 = [for scope in each.value : "${aws_cognito_resource_server.api.identifier}/${scope}"]
  supported_identity_providers         = ["COGNITO"]

  access_token_validity         = var.access_token_validity_minutes
  prevent_user_existence_errors = "ENABLED"
  enable_token_revocation       = true

  token_validity_units {
    access_token = "minutes"
  }
}
