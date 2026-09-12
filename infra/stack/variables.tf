# Defaults describe a small, cheap environment; infra/environments/prod overrides them.

variable "project" {
  description = "Prefix for resource names."
  type        = string
  default     = "banking"
}

variable "environment" {
  description = "Environment name. Must match the GitHub environment allowed to deploy it."
  type        = string

  validation {
    condition     = can(regex("^[a-z][a-z0-9-]{1,12}$", var.environment))
    error_message = "environment must be 2-13 lowercase letters, digits or hyphens."
  }
}

# --- Delivery ----------------------------------------------------------------

variable "github_repository" {
  description = "GitHub repository (owner/name) whose pipeline may deploy this environment."
  type        = string
}

variable "github_oidc_provider_arn" {
  description = "GitHub Actions OIDC provider (infra/bootstrap output)."
  type        = string
}

variable "ecr_repository_url" {
  description = "Image repository URL (infra/bootstrap output)."
  type        = string
}

variable "ecr_repository_arn" {
  description = "Image repository ARN (infra/bootstrap output)."
  type        = string
}

variable "image_tag" {
  description = "Tag of an image already in the repository, used when the task definitions are first created. Releases after that are deployed by the pipeline."
  type        = string
}

# --- Network and edge ----------------------------------------------------------

variable "vpc_cidr" {
  type    = string
  default = "10.20.0.0/16"
}

variable "az_count" {
  type    = number
  default = 2
}

variable "single_nat_gateway" {
  description = "One shared NAT gateway instead of one per availability zone."
  type        = bool
  default     = true
}

variable "allowed_ingress_cidrs" {
  description = "Client networks allowed to reach the load balancer."
  type        = list(string)
  default     = ["0.0.0.0/0"]
}

variable "domain_name" {
  description = "API domain name with an ACM certificate. Null uses the load balancer DNS name and a self-signed certificate."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Hosted zone for domain_name."
  type        = string
  default     = null
}

variable "waf_rate_limit" {
  description = "Requests per client IP per 5 minutes before WAF blocks the IP."
  type        = number
  default     = 2000
}

# --- Application servers -------------------------------------------------------

variable "api_cpu" {
  type    = number
  default = 512
}

variable "api_memory" {
  type    = number
  default = 1024
}

variable "api_min_capacity" {
  type    = number
  default = 2
}

variable "api_max_capacity" {
  type    = number
  default = 4
}

variable "cpu_architecture" {
  type    = string
  default = "ARM64"
}

variable "business_time_zone" {
  description = "Time zone whose midnight resets the daily withdrawal limit."
  type        = string
  default     = "UTC"
}

variable "payment_acceptance_window" {
  description = "ISO-8601 duration held payment funds wait for acceptance."
  type        = string
  default     = "P3D"
}

# --- Database ----------------------------------------------------------------

variable "db_instance_class" {
  type    = string
  default = "db.t4g.micro"
}

variable "db_allocated_storage" {
  type    = number
  default = 20
}

variable "db_max_allocated_storage" {
  type    = number
  default = 100
}

variable "db_multi_az" {
  type    = bool
  default = false
}

variable "db_backup_retention_days" {
  type    = number
  default = 7
}

variable "db_performance_insights_enabled" {
  type    = bool
  default = false
}

variable "db_connections_alarm_threshold" {
  description = "Alarm when database connections exceed this (keep below the instance class's max_connections)."
  type        = number
  default     = 80
}

# --- Operations --------------------------------------------------------------

variable "protect_from_deletion" {
  description = "Deletion protection on the database, load balancer and user pool, and a final database snapshot."
  type        = bool
  default     = true
}

variable "log_retention_days" {
  description = "Retention for application, database, WAF and VPC flow logs."
  type        = number
  default     = 90

  validation {
    condition     = contains([1, 3, 5, 7, 14, 30, 60, 90, 120, 150, 180, 365, 400, 545, 731, 1096, 1827, 2192, 2557, 2922, 3288, 3653], var.log_retention_days)
    error_message = "log_retention_days must be a retention period CloudWatch Logs supports."
  }
}

variable "alarm_email" {
  description = "Email address for alarm notifications. Null for none."
  type        = string
  default     = null
}
