variable "name" {
  description = "Name prefix, e.g. banking-dev."
  type        = string
}

variable "cidr_block" {
  description = "VPC CIDR block; a /16 is expected."
  type        = string

  validation {
    condition     = can(cidrnetmask(var.cidr_block)) && endswith(var.cidr_block, "/16")
    error_message = "cidr_block must be a valid /16 CIDR block."
  }
}

variable "az_count" {
  description = "Number of availability zones to span."
  type        = number
  default     = 2

  validation {
    condition     = var.az_count >= 2 && var.az_count <= 3
    error_message = "az_count must be 2 or 3 (RDS and the load balancer need at least two)."
  }
}

variable "single_nat_gateway" {
  description = "Share one NAT gateway across AZs (cheaper; outbound traffic depends on one AZ)."
  type        = bool
}

variable "log_retention_days" {
  description = "Retention for VPC flow logs."
  type        = number
}

variable "kms_key_arn" {
  description = "KMS key that encrypts the flow log group."
  type        = string
}
