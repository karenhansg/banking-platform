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
      Environment = "prod"
      ManagedBy   = "terraform"
      Repository  = var.github_repository
    }
  }
}

# Production: three availability zones with a NAT gateway in each, a Multi-AZ database with 35 days of
# point-in-time recovery, at least three API tasks, deletion protection and a year of logs.
module "banking" {
  source = "../../stack"

  environment              = "prod"
  github_repository        = var.github_repository
  github_oidc_provider_arn = var.github_oidc_provider_arn
  ecr_repository_url       = var.ecr_repository_url
  ecr_repository_arn       = var.ecr_repository_arn
  image_tag                = var.image_tag
  domain_name              = var.domain_name
  route53_zone_id          = var.route53_zone_id
  alarm_email              = var.alarm_email
  business_time_zone       = var.business_time_zone

  vpc_cidr           = "10.30.0.0/16"
  az_count           = 3
  single_nat_gateway = false

  api_cpu          = 1024
  api_memory       = 2048
  api_min_capacity = 3
  api_max_capacity = 10

  db_instance_class               = "db.m7g.large"
  db_allocated_storage            = 100
  db_max_allocated_storage        = 1000
  db_multi_az                     = true
  db_backup_retention_days        = 35
  db_performance_insights_enabled = true
  db_connections_alarm_threshold  = 500

  payment_acceptance_window = "P3D"

  protect_from_deletion = true
  log_retention_days    = 365
}
