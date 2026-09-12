variable "name" {
  description = "Name prefix (at most 28 characters, as it is also used for the target group name)."
  type        = string

  validation {
    condition     = length(var.name) <= 28
    error_message = "name must be at most 28 characters."
  }
}

variable "vpc_id" {
  type = string
}

variable "public_subnet_ids" {
  type = list(string)
}

variable "security_group_id" {
  description = "Security group for the load balancer."
  type        = string
}

variable "app_port" {
  description = "Port the application containers listen on."
  type        = number
  default     = 8080
}

variable "health_check_path" {
  description = "Application readiness endpoint used for target health checks."
  type        = string
  default     = "/actuator/health/readiness"
}

variable "domain_name" {
  description = "Public DNS name for the API, e.g. api.dev.example.com. Leave null to use the load balancer's DNS name with a self-signed certificate."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Route 53 hosted zone that contains domain_name."
  type        = string
  default     = null

  validation {
    condition     = var.domain_name == null || var.route53_zone_id != null
    error_message = "route53_zone_id is required when domain_name is set."
  }
}

variable "deletion_protection" {
  description = "Protect the load balancer and its access logs from deletion."
  type        = bool
}

variable "access_log_retention_days" {
  description = "Days to keep load balancer access logs."
  type        = number
  default     = 90
}

variable "log_retention_days" {
  description = "Retention for WAF logs."
  type        = number
}

variable "kms_key_arn" {
  description = "KMS key that encrypts the WAF log group."
  type        = string
}

variable "waf_rate_limit" {
  description = "Requests allowed per client IP in any 5-minute window before WAF blocks that IP."
  type        = number
  default     = 2000
}
