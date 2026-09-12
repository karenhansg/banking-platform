terraform {
  required_version = ">= 1.10"

  required_providers {
    aws = {
      source  = "hashicorp/aws"
      version = "~> 6.0"
    }
    random = {
      source  = "hashicorp/random"
      version = "~> 3.6"
    }
    tls = {
      source  = "hashicorp/tls"
      version = "~> 4.0"
    }
  }
}

provider "aws" {
  region = var.region

  default_tags {
    tags = {
      Project     = "banking"
      Environment = "dev"
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}

# Development trades resilience for cost: one NAT gateway, a small single-AZ database and no deletion protection.
# The API still runs two tasks in two availability zones, so deployments and failover behave as in production.
module "banking" {
  source = "../../stack"

  environment              = "dev"
  github_repository        = var.github_repository
  github_oidc_provider_arn = var.github_oidc_provider_arn
  ecr_repository_url       = var.ecr_repository_url
  ecr_repository_arn       = var.ecr_repository_arn
  image_tag                = var.image_tag
  domain_name              = var.domain_name
  route53_zone_id          = var.route53_zone_id
  alarm_email              = var.alarm_email
  business_time_zone       = var.business_time_zone

  vpc_cidr           = "10.20.0.0/16"
  az_count           = 2
  single_nat_gateway = true

  api_cpu          = 512
  api_memory       = 1024
  api_min_capacity = 2
  api_max_capacity = 4

  db_instance_class               = "db.t4g.micro"
  db_multi_az                     = false
  db_backup_retention_days        = 7
  db_performance_insights_enabled = false
  db_connections_alarm_threshold  = 80

  # Short enough to watch a payment expire during a demo.
  payment_acceptance_window = "PT30M"

  protect_from_deletion = false
  log_retention_days    = 30
}
