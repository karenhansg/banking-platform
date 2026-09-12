output "api_base_url" {
  description = "Base URL of the API."
  value       = module.load_balancer.api_base_url
}

output "uses_self_signed_certificate" {
  description = "True when no domain is configured; clients must then skip certificate verification (curl -k)."
  value       = module.load_balancer.uses_self_signed_certificate
}

output "token_endpoint" {
  description = "OAuth2 token endpoint for API clients."
  value       = module.auth.token_endpoint
}

output "user_pool_id" {
  value = module.auth.user_pool_id
}

output "client_ids" {
  description = "API client IDs by name. Read a client secret with: aws cognito-idp describe-user-pool-client --user-pool-id <user_pool_id> --client-id <id> --query UserPoolClient.ClientSecret --output text"
  value       = module.auth.client_ids
}

output "database_endpoint" {
  value = "${module.database.address}:${module.database.port}"
}

output "dashboard_url" {
  value = module.monitoring.dashboard_url
}

output "alarm_topic_arn" {
  value = module.monitoring.alarm_topic_arn
}

output "migration_log_group" {
  value = module.ecs_service.migrate_log_group_name
}

output "github_environment_variables" {
  description = "Variables for the GitHub environment named after this environment (Settings > Environments)."
  value = {
    AWS_DEPLOY_ROLE_ARN   = aws_iam_role.github_deploy.arn
    ECS_CLUSTER           = module.ecs_service.cluster_name
    ECS_SERVICE           = module.ecs_service.service_name
    API_TASK_FAMILY       = module.ecs_service.api_task_family
    MIGRATION_TASK_FAMILY = module.ecs_service.migrate_task_family
    API_BASE_URL          = module.load_balancer.api_base_url
    ALLOW_SELF_SIGNED_TLS = tostring(module.load_balancer.uses_self_signed_certificate)
  }
}
