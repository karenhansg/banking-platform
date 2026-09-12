# Internet-facing Application Load Balancer. TLS terminates here; only /api/* is forwarded to the application,
# so management endpoints (actuator) are unreachable from the internet.

data "aws_caller_identity" "current" {}

data "aws_partition" "current" {}

data "aws_elb_service_account" "current" {}

locals {
  use_custom_domain = var.domain_name != null
  # Exactly one of the two certificates exists.
  certificate_arn = one(concat(
    aws_acm_certificate_validation.domain[*].certificate_arn,
    aws_acm_certificate.self_signed[*].arn,
  ))
}

# -----------------------------------------------------------------------------
# TLS certificate. With a domain: a DNS-validated ACM certificate and an alias record. Without one (e.g. a
# throwaway dev environment): a self-signed certificate imported into ACM, so traffic is still encrypted.
# -----------------------------------------------------------------------------

resource "aws_acm_certificate" "domain" {
  count = local.use_custom_domain ? 1 : 0

  domain_name       = var.domain_name
  validation_method = "DNS"

  lifecycle {
    create_before_destroy = true
  }
}

resource "aws_route53_record" "certificate_validation" {
  for_each = local.use_custom_domain ? {
    for option in aws_acm_certificate.domain[0].domain_validation_options : option.domain_name => option
  } : {}

  zone_id         = var.route53_zone_id
  name            = each.value.resource_record_name
  type            = each.value.resource_record_type
  records         = [each.value.resource_record_value]
  ttl             = 300
  allow_overwrite = true
}

resource "aws_acm_certificate_validation" "domain" {
  count = local.use_custom_domain ? 1 : 0

  certificate_arn         = aws_acm_certificate.domain[0].arn
  validation_record_fqdns = [for record in aws_route53_record.certificate_validation : record.fqdn]
}

resource "aws_route53_record" "api" {
  count = local.use_custom_domain ? 1 : 0

  zone_id = var.route53_zone_id
  name    = var.domain_name
  type    = "A"

  alias {
    name                   = aws_lb.this.dns_name
    zone_id                = aws_lb.this.zone_id
    evaluate_target_health = true
  }
}

resource "tls_private_key" "self_signed" {
  count = local.use_custom_domain ? 0 : 1

  algorithm = "RSA"
  rsa_bits  = 2048
}

resource "tls_self_signed_cert" "self_signed" {
  count = local.use_custom_domain ? 0 : 1

  private_key_pem       = tls_private_key.self_signed[0].private_key_pem
  validity_period_hours = 24 * 365
  allowed_uses          = ["key_encipherment", "digital_signature", "server_auth"]

  subject {
    common_name  = "${var.name}.invalid"
    organization = "Self-signed certificate for a non-production environment"
  }
}

resource "aws_acm_certificate" "self_signed" {
  count = local.use_custom_domain ? 0 : 1

  private_key      = tls_private_key.self_signed[0].private_key_pem
  certificate_body = tls_self_signed_cert.self_signed[0].cert_pem

  lifecycle {
    create_before_destroy = true
  }
}

# -----------------------------------------------------------------------------
# Access logs
# -----------------------------------------------------------------------------

resource "aws_s3_bucket" "access_logs" {
  #checkov:skip=CKV_AWS_145:Load balancer log delivery supports only SSE-S3, not KMS keys.
  bucket_prefix = "${var.name}-alb-logs-"
  force_destroy = !var.deletion_protection
}

resource "aws_s3_bucket_public_access_block" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  block_public_acls       = true
  block_public_policy     = true
  ignore_public_acls      = true
  restrict_public_buckets = true
}

resource "aws_s3_bucket_ownership_controls" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    object_ownership = "BucketOwnerEnforced"
  }
}

# Load balancer log delivery only supports S3-managed encryption keys.
resource "aws_s3_bucket_server_side_encryption_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    apply_server_side_encryption_by_default {
      sse_algorithm = "AES256"
    }
  }
}

resource "aws_s3_bucket_versioning" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  versioning_configuration {
    status = "Enabled"
  }
}

resource "aws_s3_bucket_lifecycle_configuration" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id

  rule {
    id     = "expire-access-logs"
    status = "Enabled"

    filter {}

    expiration {
      days = var.access_log_retention_days
    }

    noncurrent_version_expiration {
      noncurrent_days = 7
    }

    abort_incomplete_multipart_upload {
      days_after_initiation = 1
    }
  }
}

resource "aws_s3_bucket_policy" "access_logs" {
  bucket = aws_s3_bucket.access_logs.id
  policy = data.aws_iam_policy_document.access_logs.json

  depends_on = [aws_s3_bucket_public_access_block.access_logs, aws_s3_bucket_ownership_controls.access_logs]
}

data "aws_iam_policy_document" "access_logs" {
  # Regions launched before August 2022 deliver logs as the regional Elastic Load Balancing account...
  statement {
    sid       = "LoadBalancerAccountLogDelivery"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "AWS"
      identifiers = [data.aws_elb_service_account.current.arn]
    }
  }

  # ...newer Regions use the log delivery service principal.
  statement {
    sid       = "LogDeliveryServiceLogDelivery"
    actions   = ["s3:PutObject"]
    resources = ["${aws_s3_bucket.access_logs.arn}/AWSLogs/${data.aws_caller_identity.current.account_id}/*"]

    principals {
      type        = "Service"
      identifiers = ["logdelivery.elasticloadbalancing.amazonaws.com"]
    }
  }

  statement {
    sid       = "DenyInsecureTransport"
    effect    = "Deny"
    actions   = ["s3:*"]
    resources = [aws_s3_bucket.access_logs.arn, "${aws_s3_bucket.access_logs.arn}/*"]

    principals {
      type        = "*"
      identifiers = ["*"]
    }

    condition {
      test     = "Bool"
      variable = "aws:SecureTransport"
      values   = ["false"]
    }
  }
}

# -----------------------------------------------------------------------------
# Load balancer
# -----------------------------------------------------------------------------

resource "aws_lb" "this" {
  name                       = var.name
  load_balancer_type         = "application"
  internal                   = false
  security_groups            = [var.security_group_id]
  subnets                    = var.public_subnet_ids
  drop_invalid_header_fields = true
  enable_deletion_protection = var.deletion_protection
  idle_timeout               = 60

  access_logs {
    bucket  = aws_s3_bucket.access_logs.id
    enabled = true
  }

  depends_on = [aws_s3_bucket_policy.access_logs]
}

resource "aws_lb_target_group" "app" {
  name        = "${var.name}-app"
  port        = var.app_port
  protocol    = "HTTP"
  target_type = "ip"
  vpc_id      = var.vpc_id
  # Matches the application's graceful shutdown: in-flight requests complete before a task is stopped.
  deregistration_delay = 30

  health_check {
    path                = var.health_check_path
    matcher             = "200"
    interval            = 15
    timeout             = 5
    healthy_threshold   = 2
    unhealthy_threshold = 3
  }
}

resource "aws_lb_listener" "https" {
  load_balancer_arn = aws_lb.this.arn
  port              = 443
  protocol          = "HTTPS"
  ssl_policy        = "ELBSecurityPolicy-TLS13-1-2-2021-06"
  certificate_arn   = local.certificate_arn

  default_action {
    type = "fixed-response"

    fixed_response {
      content_type = "application/json"
      message_body = jsonencode({ status = 404, title = "Not Found" })
      status_code  = "404"
    }
  }
}

resource "aws_lb_listener_rule" "api" {
  listener_arn = aws_lb_listener.https.arn
  priority     = 100

  action {
    type             = "forward"
    target_group_arn = aws_lb_target_group.app.arn
  }

  condition {
    path_pattern {
      values = ["/api/*"]
    }
  }
}

# Deliberately no port 80 listener. Redirecting HTTP to HTTPS suits browsers, but an API client misconfigured with
# an http:// URL would already have sent its bearer token in cleartext before seeing the redirect. Without a
# listener the connection is refused before any request is sent.
