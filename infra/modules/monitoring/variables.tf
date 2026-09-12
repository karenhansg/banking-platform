variable "name" {
  description = "Resource prefix, e.g. banking-dev."
  type        = string
}

variable "kms_key_arn" {
  description = "KMS key that encrypts the alarm topic."
  type        = string
}

variable "alarm_email" {
  description = "Email address subscribed to alarm notifications (the subscription must be confirmed). Null for none."
  type        = string
  default     = null
}

variable "alb_arn_suffix" {
  type = string
}

variable "target_group_arn_suffix" {
  type = string
}

variable "ecs_cluster_name" {
  type = string
}

variable "ecs_service_name" {
  type = string
}

variable "db_instance_identifier" {
  type = string
}

variable "api_log_group_name" {
  description = "Log group with the API's structured JSON logs."
  type        = string
}

variable "waf_web_acl_name" {
  type = string
}

variable "latency_p95_threshold_seconds" {
  description = "p95 response time above which the latency alarm fires."
  type        = number
  default     = 1
}

variable "db_connections_alarm_threshold" {
  description = "Database connections above which the connections alarm fires."
  type        = number
  default     = 80
}
