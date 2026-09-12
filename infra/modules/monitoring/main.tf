# Alarms notify an encrypted SNS topic. Application and business metrics come from the application's structured
# JSON logs through metric filters, so no metrics agent or extra AWS permissions are needed in the tasks.

data "aws_caller_identity" "current" {}

data "aws_region" "current" {}

locals {
  region            = data.aws_region.current.region
  metrics_namespace = "Banking/${var.name}"
  alarm_actions     = [aws_sns_topic.alarms.arn]
}

resource "aws_sns_topic" "alarms" {
  name              = "${var.name}-alarms"
  kms_master_key_id = var.kms_key_arn
}

resource "aws_sns_topic_policy" "alarms" {
  arn    = aws_sns_topic.alarms.arn
  policy = data.aws_iam_policy_document.alarms_topic.json
}

data "aws_iam_policy_document" "alarms_topic" {
  statement {
    sid       = "CloudWatchAlarmsPublish"
    actions   = ["sns:Publish"]
    resources = [aws_sns_topic.alarms.arn]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [data.aws_caller_identity.current.account_id]
    }
  }
}

resource "aws_sns_topic_subscription" "email" {
  count = var.alarm_email == null ? 0 : 1

  topic_arn = aws_sns_topic.alarms.arn
  protocol  = "email"
  endpoint  = var.alarm_email
}

# -----------------------------------------------------------------------------
# Metrics from application logs
# -----------------------------------------------------------------------------

locals {
  log_metrics = {
    ApplicationErrors        = "{ $.level = \"ERROR\" }"
    LedgerPostings           = "{ $.event = \"ledger.posting\" }"
    DailyLimitRejections     = "{ $.code = \"DAILY_WITHDRAWAL_LIMIT_EXCEEDED\" }"
    ScheduledPaymentFailures = "{ $.event = \"payment.failed\" }"
  }
}

resource "aws_cloudwatch_log_metric_filter" "application" {
  for_each = local.log_metrics

  name           = "${var.name}-${each.key}"
  log_group_name = var.api_log_group_name
  pattern        = each.value

  metric_transformation {
    name      = each.key
    namespace = local.metrics_namespace
    value     = "1"
    unit      = "Count"
  }
}

# -----------------------------------------------------------------------------
# Alarms
# -----------------------------------------------------------------------------

locals {
  alb      = { LoadBalancer = var.alb_arn_suffix }
  target   = { LoadBalancer = var.alb_arn_suffix, TargetGroup = var.target_group_arn_suffix }
  service  = { ClusterName = var.ecs_cluster_name, ServiceName = var.ecs_service_name }
  database = { DBInstanceIdentifier = var.db_instance_identifier }

  alarms = {
    "alb-5xx" = {
      description = "The load balancer itself is returning 5xx responses (typically no healthy tasks)."
      namespace   = "AWS/ApplicationELB", metric = "HTTPCode_ELB_5XX_Count", dimensions = local.alb
      statistic   = "Sum", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 5
      period      = 60, evaluation_periods = 5, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-5xx" = {
      description = "The API is returning 5xx responses."
      namespace   = "AWS/ApplicationELB", metric = "HTTPCode_Target_5XX_Count", dimensions = local.target
      statistic   = "Sum", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 5
      period      = 60, evaluation_periods = 5, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-latency-p95" = {
      description = "95th percentile API response time is above ${var.latency_p95_threshold_seconds}s."
      namespace   = "AWS/ApplicationELB", metric = "TargetResponseTime", dimensions = local.target
      statistic   = null, extended_statistic = "p95", comparison = "GreaterThanThreshold"
      threshold   = var.latency_p95_threshold_seconds
      period      = 60, evaluation_periods = 5, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-unhealthy-tasks" = {
      description = "At least one API task is failing load balancer health checks."
      namespace   = "AWS/ApplicationELB", metric = "UnHealthyHostCount", dimensions = local.target
      statistic   = "Maximum", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 0
      period      = 60, evaluation_periods = 3, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-no-healthy-tasks" = {
      description = "No API task is healthy: the API is down."
      namespace   = "AWS/ApplicationELB", metric = "HealthyHostCount", dimensions = local.target
      statistic   = "Minimum", extended_statistic = null, comparison = "LessThanThreshold", threshold = 1
      period      = 60, evaluation_periods = 2, datapoints_to_alarm = 2, treat_missing_data = "breaching"
    }
    "api-cpu-high" = {
      description = "API tasks are running hot even after scaling out."
      namespace   = "AWS/ECS", metric = "CPUUtilization", dimensions = local.service
      statistic   = "Average", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 85
      period      = 300, evaluation_periods = 3, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-memory-high" = {
      description = "API tasks are close to their memory limit."
      namespace   = "AWS/ECS", metric = "MemoryUtilization", dimensions = local.service
      statistic   = "Average", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 85
      period      = 300, evaluation_periods = 3, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "api-application-errors" = {
      description = "The application is logging errors."
      namespace   = local.metrics_namespace, metric = "ApplicationErrors", dimensions = {}
      statistic   = "Sum", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 5
      period      = 300, evaluation_periods = 1, datapoints_to_alarm = 1, treat_missing_data = "notBreaching"
    }
    "database-cpu-high" = {
      description = "Database CPU is high."
      namespace   = "AWS/RDS", metric = "CPUUtilization", dimensions = local.database
      statistic   = "Average", extended_statistic = null, comparison = "GreaterThanThreshold", threshold = 80
      period      = 300, evaluation_periods = 3, datapoints_to_alarm = 3, treat_missing_data = "notBreaching"
    }
    "database-storage-low" = {
      description = "Less than 2 GiB of database storage is free."
      namespace   = "AWS/RDS", metric = "FreeStorageSpace", dimensions = local.database
      statistic   = "Minimum", extended_statistic = null, comparison = "LessThanThreshold", threshold = 2147483648
      period      = 300, evaluation_periods = 1, datapoints_to_alarm = 1, treat_missing_data = "notBreaching"
    }
    "database-connections-high" = {
      description = "Database connections are approaching the instance limit."
      namespace   = "AWS/RDS", metric = "DatabaseConnections", dimensions = local.database
      statistic   = "Maximum", extended_statistic = null, comparison = "GreaterThanThreshold"
      threshold   = var.db_connections_alarm_threshold
      period      = 300, evaluation_periods = 2, datapoints_to_alarm = 2, treat_missing_data = "notBreaching"
    }
  }
}

resource "aws_cloudwatch_metric_alarm" "this" {
  for_each = local.alarms

  alarm_name          = "${var.name}-${each.key}"
  alarm_description   = each.value.description
  namespace           = each.value.namespace
  metric_name         = each.value.metric
  dimensions          = each.value.dimensions
  statistic           = each.value.statistic
  extended_statistic  = each.value.extended_statistic
  comparison_operator = each.value.comparison
  threshold           = each.value.threshold
  period              = each.value.period
  evaluation_periods  = each.value.evaluation_periods
  datapoints_to_alarm = each.value.datapoints_to_alarm
  treat_missing_data  = each.value.treat_missing_data
  alarm_actions       = local.alarm_actions
  ok_actions          = local.alarm_actions

  depends_on = [aws_cloudwatch_log_metric_filter.application]
}
