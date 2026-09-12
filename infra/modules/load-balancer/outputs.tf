output "dns_name" {
  value = aws_lb.this.dns_name
}

output "api_base_url" {
  description = "Base URL clients use to reach the API."
  value       = "https://${local.use_custom_domain ? var.domain_name : aws_lb.this.dns_name}"
}

output "uses_self_signed_certificate" {
  value = !local.use_custom_domain
}

output "arn_suffix" {
  description = "Load balancer dimension value for CloudWatch metrics."
  value       = aws_lb.this.arn_suffix
}

output "target_group_arn" {
  value = aws_lb_target_group.app.arn

  # ECS can only register a service with a target group that is already attached to the load balancer.
  depends_on = [aws_lb_listener_rule.api]
}

output "target_group_arn_suffix" {
  description = "Target group dimension value for CloudWatch metrics."
  value       = aws_lb_target_group.app.arn_suffix
}

output "web_acl_name" {
  value = aws_wafv2_web_acl.this.name
}
