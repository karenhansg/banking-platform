# Role the pipeline assumes to deploy this environment. The trust policy only admits jobs running in the GitHub
# environment of the same name, so GitHub's environment protection rules (e.g. required reviewers for prod) gate
# every deployment. The permissions cover exactly what scripts/deploy.sh does: run the migration task and roll
# the API service onto a new task definition revision.

resource "aws_iam_role" "github_deploy" {
  name                 = "${local.name}-github-deploy"
  assume_role_policy   = data.aws_iam_policy_document.github_deploy_trust.json
  max_session_duration = 3600
}

data "aws_iam_policy_document" "github_deploy_trust" {
  statement {
    actions = ["sts:AssumeRoleWithWebIdentity"]

    principals {
      type        = "Federated"
      identifiers = [var.github_oidc_provider_arn]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:aud"
      values   = ["sts.amazonaws.com"]
    }

    condition {
      test     = "StringEquals"
      variable = "token.actions.githubusercontent.com:sub"
      values   = ["repo:${var.github_repository}:environment:${var.environment}"]
    }
  }
}

resource "aws_iam_role_policy" "github_deploy" {
  name   = "deploy-api"
  role   = aws_iam_role.github_deploy.id
  policy = data.aws_iam_policy_document.github_deploy.json
}

data "aws_iam_policy_document" "github_deploy" {
  # These actions do not support resource-level permissions.
  statement {
    sid = "ReadAndRegisterTaskDefinitions"
    actions = [
      "ecs:DescribeTaskDefinition",
      "ecs:RegisterTaskDefinition",
      "application-autoscaling:DescribeScalableTargets",
    ]
    resources = ["*"]
  }

  statement {
    sid       = "VerifyImageExists"
    actions   = ["ecr:DescribeImages"]
    resources = [var.ecr_repository_arn]
  }

  statement {
    sid       = "RollOutApiService"
    actions   = ["ecs:DescribeServices", "ecs:UpdateService"]
    resources = [module.ecs_service.service_arn]
  }

  statement {
    sid       = "RunMigrationTask"
    actions   = ["ecs:RunTask"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:task-definition/${module.ecs_service.migrate_task_family}:*"]

    condition {
      test     = "ArnEquals"
      variable = "ecs:cluster"
      values   = [module.ecs_service.cluster_arn]
    }
  }

  statement {
    sid       = "WatchMigrationTask"
    actions   = ["ecs:DescribeTasks"]
    resources = ["arn:${local.partition}:ecs:${local.region}:${local.account_id}:task/${module.ecs_service.cluster_name}/*"]
  }

  statement {
    sid       = "PassTaskRolesToEcs"
    actions   = ["iam:PassRole"]
    resources = module.ecs_service.task_role_arns

    condition {
      test     = "StringEquals"
      variable = "iam:PassedToService"
      values   = ["ecs-tasks.amazonaws.com"]
    }
  }
}
