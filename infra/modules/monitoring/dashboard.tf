locals {
  dashboard_widgets = [
    {
      title   = "Requests"
      stat    = "Sum"
      metrics = [["AWS/ApplicationELB", "RequestCount", "LoadBalancer", var.alb_arn_suffix]]
    },
    {
      title = "Errors (5xx)"
      stat  = "Sum"
      metrics = [
        ["AWS/ApplicationELB", "HTTPCode_ELB_5XX_Count", "LoadBalancer", var.alb_arn_suffix],
        ["AWS/ApplicationELB", "HTTPCode_Target_5XX_Count", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix],
      ]
    },
    {
      title = "Response time (seconds)"
      stat  = "p95"
      metrics = [
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p50", label = "p50" }],
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p95", label = "p95" }],
        ["AWS/ApplicationELB", "TargetResponseTime", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "p99", label = "p99" }],
      ]
    },
    {
      title = "API tasks"
      stat  = "Average"
      metrics = [
        ["AWS/ECS", "CPUUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", var.ecs_service_name],
        ["AWS/ECS", "MemoryUtilization", "ClusterName", var.ecs_cluster_name, "ServiceName", var.ecs_service_name],
        ["AWS/ApplicationELB", "HealthyHostCount", "LoadBalancer", var.alb_arn_suffix, "TargetGroup", var.target_group_arn_suffix, { stat = "Minimum", yAxis = "right" }],
      ]
    },
    {
      title = "Database"
      stat  = "Average"
      metrics = [
        ["AWS/RDS", "CPUUtilization", "DBInstanceIdentifier", var.db_instance_identifier],
        ["AWS/RDS", "DatabaseConnections", "DBInstanceIdentifier", var.db_instance_identifier, { stat = "Maximum", yAxis = "right" }],
      ]
    },
    {
      title = "Business activity"
      stat  = "Sum"
      metrics = [
        [local.metrics_namespace, "LedgerPostings"],
        [local.metrics_namespace, "DailyLimitRejections", { yAxis = "right" }],
        [local.metrics_namespace, "ScheduledPaymentFailures", { yAxis = "right" }],
      ]
    },
    {
      title = "Application errors and blocked requests"
      stat  = "Sum"
      metrics = [
        [local.metrics_namespace, "ApplicationErrors"],
        ["AWS/WAFV2", "BlockedRequests", "WebACL", var.waf_web_acl_name, "Region", local.region, "Rule", "ALL", { yAxis = "right" }],
      ]
    },
  ]
}

resource "aws_cloudwatch_dashboard" "this" {
  dashboard_name = var.name

  dashboard_body = jsonencode({
    widgets = [
      for index, widget in local.dashboard_widgets : {
        type   = "metric"
        width  = 12
        height = 6
        x      = (index % 2) * 12
        y      = floor(index / 2) * 6
        properties = {
          title   = widget.title
          region  = local.region
          stat    = widget.stat
          period  = 60
          view    = "timeSeries"
          metrics = widget.metrics
        }
      }
    ]
  })
}
