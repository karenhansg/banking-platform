output "address" {
  value = aws_db_instance.this.address
}

output "port" {
  value = aws_db_instance.this.port
}

output "database_name" {
  value = aws_db_instance.this.db_name
}

output "identifier" {
  value = aws_db_instance.this.identifier
}

output "resource_id" {
  description = "Identifies the instance in IAM database authentication policies (rds-db:connect)."
  value       = aws_db_instance.this.resource_id
}

output "master_user_secret_arn" {
  description = "Secrets Manager secret holding the master credentials, managed and rotated by RDS."
  value       = aws_db_instance.this.master_user_secret[0].secret_arn
}
