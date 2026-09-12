output "api_base_url" {
  value = module.banking.api_base_url
}

output "uses_self_signed_certificate" {
  value = module.banking.uses_self_signed_certificate
}

output "token_endpoint" {
  value = module.banking.token_endpoint
}

output "user_pool_id" {
  value = module.banking.user_pool_id
}

output "client_ids" {
  value = module.banking.client_ids
}

output "database_endpoint" {
  value = module.banking.database_endpoint
}

output "dashboard_url" {
  value = module.banking.dashboard_url
}

output "migration_log_group" {
  value = module.banking.migration_log_group
}

output "github_environment_variables" {
  value = module.banking.github_environment_variables
}
