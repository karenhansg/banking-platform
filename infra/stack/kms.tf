# One customer-managed key per environment encrypts its data at rest: database storage and backups, the database
# master secret, Performance Insights, CloudWatch log groups and the alarm topic. Rotated yearly by KMS.

resource "aws_kms_key" "main" {
  description             = "Encrypts ${local.name} data at rest"
  enable_key_rotation     = true
  deletion_window_in_days = 30
  policy                  = data.aws_iam_policy_document.kms.json
}

resource "aws_kms_alias" "main" {
  name          = "alias/${local.name}"
  target_key_id = aws_kms_key.main.key_id
}

data "aws_iam_policy_document" "kms" {
  #checkov:skip=CKV_AWS_109:Key policies must grant the account root kms:*; "*" means this key only.
  #checkov:skip=CKV_AWS_111:Key policies must grant the account root kms:*; "*" means this key only.
  #checkov:skip=CKV_AWS_356:In a key policy the resource "*" refers to the key itself.

  # Delegates access control to IAM policies in this account (RDS, Secrets Manager and ECS use the key through them).
  statement {
    sid       = "AccountAdministration"
    actions   = ["kms:*"]
    resources = ["*"]

    principals {
      type        = "AWS"
      identifiers = ["arn:${local.partition}:iam::${local.account_id}:root"]
    }
  }

  statement {
    sid       = "CloudWatchLogsEncryption"
    actions   = ["kms:Encrypt*", "kms:Decrypt*", "kms:ReEncrypt*", "kms:GenerateDataKey*", "kms:Describe*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["logs.${local.region}.amazonaws.com"]
    }

    condition {
      test     = "ArnLike"
      variable = "kms:EncryptionContext:aws:logs:arn"
      values   = ["arn:${local.partition}:logs:${local.region}:${local.account_id}:log-group:*"]
    }
  }

  # CloudWatch alarms publish to the encrypted SNS topic.
  statement {
    sid       = "CloudWatchAlarmsPublishToEncryptedTopic"
    actions   = ["kms:Decrypt", "kms:GenerateDataKey*"]
    resources = ["*"]

    principals {
      type        = "Service"
      identifiers = ["cloudwatch.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "aws:SourceAccount"
      values   = [local.account_id]
    }
  }
}
