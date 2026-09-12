# Application servers: stateless API tasks on ECS Fargate in private subnets, spread across availability zones,
# registered with the load balancer and scaled on CPU. Fargate means there are no hosts to patch.
#
# Terraform owns the shape of the task definitions (resources, environment, roles). Deployments
# (scripts/deploy.sh) register new revisions that change only the image, which is why the service ignores
# task_definition changes.

data "aws_region" "current" {}

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

locals {
  region     = data.aws_region.current.region
  account_id = data.aws_caller_identity.current.account_id
  partition  = data.aws_partition.current.partition

  shared_environment = {
    DB_HOST                           = var.database.host
    DB_PORT                           = tostring(var.database.port)
    DB_NAME                           = var.database.name
    JAVA_TOOL_OPTIONS                 = "-XX:MaxRAMPercentage=75 -XX:+ExitOnOutOfMemoryError"
    LOGGING_STRUCTURED_FORMAT_CONSOLE = "logstash"
  }

  api_environment = merge(local.shared_environment, {
    SPRING_PROFILES_ACTIVE            = "aws"
    DB_USERNAME                       = var.database.app_username
    JWT_ISSUER_URI                    = var.jwt_issuer_uri
    JWT_JWK_SET_URI                   = var.jwt_jwk_set_uri
    BANKING_BUSINESS_TIME_ZONE        = var.business_time_zone
    BANKING_PAYMENT_ACCEPTANCE_WINDOW = var.payment_acceptance_window
  })

  migrate_environment = merge(local.shared_environment, {
    SPRING_PROFILES_ACTIVE = "migrate"
  })

  # Hardening shared by both containers: read-only root filesystem with a scratch volume for /tmp.
  container_defaults = {
    image                  = var.image
    essential              = true
    readonlyRootFilesystem = true
    mountPoints            = [{ sourceVolume = "tmp", containerPath = "/tmp", readOnly = false }]
  }
}

resource "aws_ecs_cluster" "this" {
  name = var.name

  setting {
    name  = "containerInsights"
    value = "enhanced"
  }
}

resource "aws_ecs_cluster_capacity_providers" "this" {
  cluster_name       = aws_ecs_cluster.this.name
  capacity_providers = ["FARGATE"]

  default_capacity_provider_strategy {
    capacity_provider = "FARGATE"
    weight            = 1
  }
}

resource "aws_cloudwatch_log_group" "api" {
  name              = "/ecs/${var.name}/api"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

resource "aws_cloudwatch_log_group" "migrate" {
  name              = "/ecs/${var.name}/migrate"
  retention_in_days = var.log_retention_days
  kms_key_id        = var.kms_key_arn
}

# -----------------------------------------------------------------------------
# API service
# -----------------------------------------------------------------------------

resource "aws_ecs_task_definition" "api" {
  family                   = "${var.name}-api"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = var.cpu
  memory                   = var.memory
  execution_role_arn       = aws_iam_role.api_execution.arn
  task_role_arn            = aws_iam_role.api_task.arn

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    merge(local.container_defaults, {
      name         = "api"
      portMappings = [{ containerPort = var.container_port, protocol = "tcp" }]
      environment  = [for key, value in local.api_environment : { name = key, value = value }]
      # Time for graceful shutdown (in-flight requests finish) before the container is killed.
      stopTimeout = 30
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.api.name
          awslogs-region        = local.region
          awslogs-stream-prefix = "api"
          # A slow logging backend must not stall request handling. The ledger in the database, not the logs,
          # is the system of record for money movements.
          mode            = "non-blocking"
          max-buffer-size = "25m"
        }
      }
    })
  ])
}

resource "aws_ecs_service" "api" {
  name                              = "api"
  cluster                           = aws_ecs_cluster.this.id
  task_definition                   = aws_ecs_task_definition.api.arn
  launch_type                       = "FARGATE"
  platform_version                  = "LATEST"
  health_check_grace_period_seconds = 90
  enable_execute_command            = false
  propagate_tags                    = "SERVICE"
  availability_zone_rebalancing     = "ENABLED"

  # Created with no tasks: the schema (and the application's database role) only exists once the first
  # deployment has run the migrations. scripts/deploy.sh then scales the service up to its minimum capacity.
  desired_count = 0

  # Rolling deployment that never drops below full capacity; a deployment whose tasks fail to become healthy
  # is rolled back automatically.
  deployment_minimum_healthy_percent = 100
  deployment_maximum_percent         = 200

  deployment_circuit_breaker {
    enable   = true
    rollback = true
  }

  network_configuration {
    subnets          = var.private_subnet_ids
    security_groups  = [var.security_group_id]
    assign_public_ip = false
  }

  load_balancer {
    target_group_arn = var.target_group_arn
    container_name   = "api"
    container_port   = var.container_port
  }

  lifecycle {
    # Deployments own the task definition revision; autoscaling owns the task count.
    ignore_changes = [task_definition, desired_count]
  }

  depends_on = [aws_iam_role_policy.api_execution, aws_iam_role_policy.api_task]
}

resource "aws_appautoscaling_target" "api" {
  service_namespace  = "ecs"
  resource_id        = "service/${aws_ecs_cluster.this.name}/${aws_ecs_service.api.name}"
  scalable_dimension = "ecs:service:DesiredCount"
  min_capacity       = var.min_capacity
  max_capacity       = var.max_capacity
}

resource "aws_appautoscaling_policy" "api_cpu" {
  name               = "${var.name}-api-cpu"
  policy_type        = "TargetTrackingScaling"
  service_namespace  = aws_appautoscaling_target.api.service_namespace
  resource_id        = aws_appautoscaling_target.api.resource_id
  scalable_dimension = aws_appautoscaling_target.api.scalable_dimension

  target_tracking_scaling_policy_configuration {
    target_value       = var.cpu_target_utilization
    scale_out_cooldown = 60
    scale_in_cooldown  = 300

    predefined_metric_specification {
      predefined_metric_type = "ECSServiceAverageCPUUtilization"
    }
  }
}

# -----------------------------------------------------------------------------
# One-off migration task (run by scripts/deploy.sh before each rollout)
# -----------------------------------------------------------------------------

resource "aws_ecs_task_definition" "migrate" {
  family                   = "${var.name}-migrate"
  requires_compatibilities = ["FARGATE"]
  network_mode             = "awsvpc"
  cpu                      = 512
  memory                   = 1024
  execution_role_arn       = aws_iam_role.migrate_execution.arn
  # No task role: the migration makes no AWS API calls.

  runtime_platform {
    operating_system_family = "LINUX"
    cpu_architecture        = var.cpu_architecture
  }

  volume {
    name = "tmp"
  }

  container_definitions = jsonencode([
    merge(local.container_defaults, {
      name        = "migrate"
      environment = [for key, value in local.migrate_environment : { name = key, value = value }]
      # Injected by ECS at start-up from the RDS-managed secret, so the task always gets the current password.
      secrets = [
        { name = "DB_MASTER_USERNAME", valueFrom = "${var.database.master_secret_arn}:username::" },
        { name = "DB_MASTER_PASSWORD", valueFrom = "${var.database.master_secret_arn}:password::" },
      ]
      logConfiguration = {
        logDriver = "awslogs"
        options = {
          awslogs-group         = aws_cloudwatch_log_group.migrate.name
          awslogs-region        = local.region
          awslogs-stream-prefix = "migrate"
        }
      }
    })
  ])
}
