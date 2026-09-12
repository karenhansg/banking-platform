variable "name" {
  description = "Instance identifier and name prefix, e.g. banking-dev."
  type        = string
}

variable "subnet_ids" {
  description = "Isolated database subnets, in at least two availability zones."
  type        = list(string)
}

variable "security_group_id" {
  description = "Security group that admits PostgreSQL traffic from the application only."
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key for storage, the master user secret, Performance Insights and exported logs."
  type        = string
}

variable "engine_version" {
  description = "PostgreSQL version. A major version alone (e.g. 17) lets RDS pick and upgrade the minor version."
  type        = string
  default     = "17"
}

variable "instance_class" {
  description = "RDS instance class."
  type        = string
}

variable "allocated_storage" {
  description = "Initial storage in GiB."
  type        = number
  default     = 20
}

variable "max_allocated_storage" {
  description = "Storage autoscaling ceiling in GiB."
  type        = number
  default     = 100
}

variable "multi_az" {
  description = "Keep a synchronous standby in a second availability zone with automatic failover."
  type        = bool
}

variable "database_name" {
  description = "Name of the application database."
  type        = string
  default     = "banking"
}

variable "master_username" {
  description = "Master (schema owner) user name. Its password is managed by RDS in Secrets Manager."
  type        = string
  default     = "banking_admin"
}

variable "backup_retention_days" {
  description = "Days of automated backups (point-in-time recovery window)."
  type        = number

  validation {
    condition     = var.backup_retention_days >= 1 && var.backup_retention_days <= 35
    error_message = "backup_retention_days must be between 1 and 35."
  }
}

variable "deletion_protection" {
  description = "Prevent the instance from being deleted."
  type        = bool
}

variable "skip_final_snapshot" {
  description = "Skip the final snapshot when the instance is deleted (disposable environments only)."
  type        = bool
}

variable "performance_insights_enabled" {
  description = "Enable Performance Insights (not available on the smallest instance classes)."
  type        = bool
}

variable "monitoring_interval" {
  description = "Enhanced Monitoring interval in seconds; 0 disables it."
  type        = number
  default     = 60

  validation {
    condition     = contains([0, 1, 5, 10, 15, 30, 60], var.monitoring_interval)
    error_message = "monitoring_interval must be one of 0, 1, 5, 10, 15, 30 or 60."
  }
}

variable "log_retention_days" {
  description = "Retention for exported PostgreSQL logs."
  type        = number
}

variable "apply_immediately" {
  description = "Apply modifications immediately instead of in the next maintenance window."
  type        = bool
  default     = false
}
