variable "region" {
  description = "AWS region for the shared resources (must match the environments)."
  type        = string
  default     = "us-east-1"
}

variable "project" {
  description = "Prefix for resource names."
  type        = string
  default     = "banking"
}

variable "github_repository" {
  description = "GitHub repository allowed to publish images, as owner/name."
  type        = string

  validation {
    condition     = can(regex("^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$", var.github_repository))
    error_message = "github_repository must look like owner/name."
  }
}

variable "create_github_oidc_provider" {
  description = "Create the GitHub Actions OIDC identity provider. Set to false if the account already has one (there can be only one per account)."
  type        = bool
  default     = true
}
