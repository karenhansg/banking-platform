variable "region" {
  description = "AWS region (the same as infra/bootstrap)."
  type        = string
  default     = "us-east-1"
}

variable "github_repository" {
  description = "GitHub repository as owner/name."
  type        = string
}

variable "github_oidc_provider_arn" {
  description = "infra/bootstrap output github_oidc_provider_arn."
  type        = string
}

variable "ecr_repository_url" {
  description = "infra/bootstrap output ecr_repository_url."
  type        = string
}

variable "ecr_repository_arn" {
  description = "infra/bootstrap output ecr_repository_arn."
  type        = string
}

variable "image_tag" {
  description = "Tag (git SHA) of an image already pushed to the repository."
  type        = string
}

variable "domain_name" {
  description = "Optional API domain name, e.g. api.dev.example.com."
  type        = string
  default     = null
}

variable "route53_zone_id" {
  description = "Hosted zone for domain_name."
  type        = string
  default     = null
}

variable "alarm_email" {
  description = "Optional email address for alarm notifications."
  type        = string
  default     = null
}

variable "business_time_zone" {
  description = "Time zone whose midnight resets the daily withdrawal limit, e.g. Asia/Singapore."
  type        = string
  default     = "UTC"
}
