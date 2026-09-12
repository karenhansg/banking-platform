# Least-privilege roles. Execution roles are used by ECS itself (pull the image, write logs, inject secrets);
# the task role is what the running application may do in AWS.

data "aws_iam_policy_document" "ecs_tasks_trust" {
  statement {
    actions = ["sts:AssumeRole"]

    principals {
      type        = "Service"
      identifiers = ["ecs-tasks.amazonaws.com"]
    }

    # Guards against the confused deputy problem: only ECS acting for this account may assume the role.
    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }

    condition {
      test     = "ArnLike"
      variable = "aws:SourceArn"
      values   = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:*"]
    }
  }
}

data "aws_iam_policy_document" "pull_image" {
  statement {
    sid       = "RegistryLogin"
    actions   = ["ecr:GetAuthorizationToken"]
    resources = ["*"]
  }

  statement {
    sid       = "PullImage"
    actions   = ["ecr:BatchCheckLayerAvailability", "ecr:BatchGetImage", "ecr:GetDownloadUrlForLayer"]
    resources = [var.ecr_repository_arn]
  }
}

# -----------------------------------------------------------------------------
# API
# -----------------------------------------------------------------------------

resource "aws_iam_role" "api_execution" {
  name               = "${var.name}-api-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "api_execution" {
  name   = "pull-image-and-write-logs"
  role   = aws_iam_role.api_execution.id
  policy = data.aws_iam_policy_document.api_execution.json
}

data "aws_iam_policy_document" "api_execution" {
  source_policy_documents = [data.aws_iam_policy_document.pull_image.json]

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.api.arn}:*"]
  }
}

resource "aws_iam_role" "api_task" {
  name               = "${var.name}-api-task"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "api_task" {
  name   = "connect-to-database"
  role   = aws_iam_role.api_task.id
  policy = data.aws_iam_policy_document.api_task.json
}

data "aws_iam_policy_document" "api_task" {
  # IAM database authentication as the application role only; the master credentials are out of reach.
  statement {
    sid       = "ConnectAsApplicationUser"
    actions   = ["rds-db:connect"]
    resources = ["arn:${local.partition}:rds-db:${local.region}:${local.account_id}:dbuser:${var.database.resource_id}/${var.database.app_username}"]
  }
}

# -----------------------------------------------------------------------------
# Migrations
# -----------------------------------------------------------------------------

resource "aws_iam_role" "migrate_execution" {
  name               = "${var.name}-migrate-execution"
  assume_role_policy = data.aws_iam_policy_document.ecs_tasks_trust.json
}

resource "aws_iam_role_policy" "migrate_execution" {
  name   = "pull-image-write-logs-read-master-secret"
  role   = aws_iam_role.migrate_execution.id
  policy = data.aws_iam_policy_document.migrate_execution.json
}

data "aws_iam_policy_document" "migrate_execution" {
  source_policy_documents = [data.aws_iam_policy_document.pull_image.json]

  statement {
    sid       = "WriteLogs"
    actions   = ["logs:CreateLogStream", "logs:PutLogEvents"]
    resources = ["${aws_cloudwatch_log_group.migrate.arn}:*"]
  }

  statement {
    sid       = "ReadDatabaseMasterSecret"
    actions   = ["secretsmanager:GetSecretValue"]
    resources = [var.database.master_secret_arn]
  }

  statement {
    sid       = "DecryptDatabaseMasterSecret"
    actions   = ["kms:Decrypt"]
    resources = [var.kms_key_arn]

    condition {
      test     = "StringEquals"
      variable = "kms:ViaService"
      values   = ["secretsmanager.${local.region}.amazonaws.com"]
    }
  }
}
