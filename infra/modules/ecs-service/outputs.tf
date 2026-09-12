output "cluster_name" {
  value = aws_ecs_cluster.this.name
}

output "cluster_arn" {
  value = aws_ecs_cluster.this.arn
}

output "service_name" {
  value = aws_ecs_service.api.name
}

output "service_arn" {
  value = aws_ecs_service.api.id
}

output "api_task_family" {
  value = aws_ecs_task_definition.api.family
}

output "migrate_task_family" {
  value = aws_ecs_task_definition.migrate.family
}

output "api_log_group_name" {
  value = aws_cloudwatch_log_group.api.name
}

output "migrate_log_group_arn" {
  value = aws_cloudwatch_log_group.migrate.arn
}

output "migrate_log_group_name" {
  value = aws_cloudwatch_log_group.migrate.name
}

output "task_role_arns" {
  description = "Roles a deployment passes to ECS when registering task definitions."
  value = [
    aws_iam_role.api_execution.arn,
    aws_iam_role.api_task.arn,
    aws_iam_role.migrate_execution.arn,
  ]
}
