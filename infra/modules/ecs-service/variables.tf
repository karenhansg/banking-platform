variable "name" {
  description = "Cluster name and resource prefix, e.g. banking-dev."
  type        = string
}

variable "image" {
  description = "Image the task definitions are created with. Subsequent releases are rolled out by scripts/deploy.sh."
  type        = string
}

variable "ecr_repository_arn" {
  description = "Repository the tasks may pull images from."
  type        = string
}

variable "private_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  description = "Security group for the application tasks."
  type        = string
}

variable "target_group_arn" {
  description = "Load balancer target group for the API tasks."
  type        = string
}

variable "container_port" {
  type    = number
  default = 8080
}

variable "cpu" {
  description = "Task CPU units (1024 = 1 vCPU)."
  type        = number
}

variable "memory" {
  description = "Task memory in MiB."
  type        = number
}

variable "cpu_architecture" {
  description = "ARM64 (Graviton; cheaper) or X86_64. Must match the image platform."
  type        = string
  default     = "ARM64"

  validation {
    condition     = contains(["ARM64", "X86_64"], var.cpu_architecture)
    error_message = "cpu_architecture must be ARM64 or X86_64."
  }
}

variable "min_capacity" {
  description = "Minimum number of API tasks; at least 2 keeps the API available if an availability zone fails."
  type        = number
}

variable "max_capacity" {
  description = "Maximum number of API tasks. Each task opens up to 10 database connections."
  type        = number
}

variable "cpu_target_utilization" {
  description = "Average CPU utilisation (percent) that autoscaling maintains."
  type        = number
  default     = 60
}

variable "log_retention_days" {
  type = number
}

variable "kms_key_arn" {
  description = "KMS key for the log groups; also decrypts the database master secret for migrations."
  type        = string
}

variable "database" {
  description = "Connection details. app_username signs in with IAM auth; master_secret_arn is used by migrations only."
  type = object({
    host              = string
    port              = number
    name              = string
    resource_id       = string
    app_username      = string
    master_secret_arn = string
  })
}

variable "jwt_issuer_uri" {
  description = "Issuer that access tokens must come from."
  type        = string
}

variable "jwt_jwk_set_uri" {
  description = "Public keys used to verify access token signatures."
  type        = string
}

variable "business_time_zone" {
  description = "Time zone whose midnight resets the daily withdrawal limit."
  type        = string
}

variable "payment_acceptance_window" {
  description = "ISO-8601 duration a scheduled payment waits for acceptance before its funds are returned."
  type        = string
}
