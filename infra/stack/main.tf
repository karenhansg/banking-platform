# One complete environment of the banking platform. infra/environments/<name> instantiates this with its sizing.
#
#   internet ── WAF ── ALB (public subnets, TLS) ── ECS Fargate API tasks (private subnets) ── RDS PostgreSQL
#                                                         │                                    (database subnets)
#                                                         └── Cognito (JWT verification keys)

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

data "aws_partition" "current" {}

locals {
  name       = "${var.project}-${var.environment}"
  account_id = data.aws_caller_identity.current.account_id
  region     = data.aws_region.current.region
  partition  = data.aws_partition.current.partition

  # Created by the Flyway migration V3__create_application_role.sql.
  database_app_username = "banking_app"
}

module "network" {
  source = "../modules/network"

  name               = local.name
  cidr_block         = var.vpc_cidr
  az_count           = var.az_count
  single_nat_gateway = var.single_nat_gateway
  log_retention_days = var.log_retention_days
  kms_key_arn        = aws_kms_key.main.arn
}

module "database" {
  source = "../modules/database"

  name                         = local.name
  subnet_ids                   = module.network.database_subnet_ids
  security_group_id            = aws_security_group.database.id
  kms_key_arn                  = aws_kms_key.main.arn
  instance_class               = var.db_instance_class
  allocated_storage            = var.db_allocated_storage
  max_allocated_storage        = var.db_max_allocated_storage
  multi_az                     = var.db_multi_az
  backup_retention_days        = var.db_backup_retention_days
  performance_insights_enabled = var.db_performance_insights_enabled
  deletion_protection          = var.protect_from_deletion
  skip_final_snapshot          = !var.protect_from_deletion
  log_retention_days           = var.log_retention_days
}

module "auth" {
  source = "../modules/auth"

  name                = local.name
  deletion_protection = var.protect_from_deletion
}

module "load_balancer" {
  source = "../modules/load-balancer"

  name                = local.name
  vpc_id              = module.network.vpc_id
  public_subnet_ids   = module.network.public_subnet_ids
  security_group_id   = aws_security_group.alb.id
  domain_name         = var.domain_name
  route53_zone_id     = var.route53_zone_id
  deletion_protection = var.protect_from_deletion
  waf_rate_limit      = var.waf_rate_limit
  log_retention_days  = var.log_retention_days
  kms_key_arn         = aws_kms_key.main.arn
}

module "ecs_service" {
  source = "../modules/ecs-service"

  name               = local.name
  image              = "${var.ecr_repository_url}:${var.image_tag}"
  ecr_repository_arn = var.ecr_repository_arn
  private_subnet_ids = module.network.private_subnet_ids
  security_group_id  = aws_security_group.app.id
  target_group_arn   = module.load_balancer.target_group_arn
  cpu                = var.api_cpu
  memory             = var.api_memory
  cpu_architecture   = var.cpu_architecture
  min_capacity       = var.api_min_capacity
  max_capacity       = var.api_max_capacity
  log_retention_days = var.log_retention_days
  kms_key_arn        = aws_kms_key.main.arn

  database = {
    host              = module.database.address
    port              = module.database.port
    name              = module.database.database_name
    resource_id       = module.database.resource_id
    app_username      = local.database_app_username
    master_secret_arn = module.database.master_user_secret_arn
  }

  jwt_issuer_uri            = module.auth.issuer_uri
  jwt_jwk_set_uri           = module.auth.jwk_set_uri
  business_time_zone        = var.business_time_zone
  payment_acceptance_window = var.payment_acceptance_window
}

module "monitoring" {
  source = "../modules/monitoring"

  name                           = local.name
  kms_key_arn                    = aws_kms_key.main.arn
  alarm_email                    = var.alarm_email
  alb_arn_suffix                 = module.load_balancer.arn_suffix
  target_group_arn_suffix        = module.load_balancer.target_group_arn_suffix
  ecs_cluster_name               = module.ecs_service.cluster_name
  ecs_service_name               = module.ecs_service.service_name
  db_instance_identifier         = module.database.identifier
  db_connections_alarm_threshold = var.db_connections_alarm_threshold
  api_log_group_name             = module.ecs_service.api_log_group_name
  waf_web_acl_name               = module.load_balancer.web_acl_name
}
