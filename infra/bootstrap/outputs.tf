output "terraform_state_bucket" {
  description = "S3 bucket for the environments' remote state (backend.hcl: bucket)."
  value       = aws_s3_bucket.terraform_state.bucket
}

output "terraform_state_kms_key_arn" {
  description = "KMS key that encrypts the state (backend.hcl: kms_key_id)."
  value       = aws_kms_key.terraform_state.arn
}

output "ecr_repository_url" {
  description = "Registry URL for API images (GitHub variable ECR_REPOSITORY_URL; environment variable ecr_repository_url)."
  value       = aws_ecr_repository.api.repository_url
}

output "ecr_repository_arn" {
  description = "Registry ARN (environment variable ecr_repository_arn)."
  value       = aws_ecr_repository.api.arn
}

output "github_oidc_provider_arn" {
  description = "GitHub Actions identity provider (environment variable github_oidc_provider_arn)."
  value       = local.github_oidc_provider_arn
}

output "github_image_publisher_role_arn" {
  description = "Role the pipeline assumes to push images (GitHub variable AWS_IMAGE_PUBLISHER_ROLE_ARN)."
  value       = aws_iam_role.github_image_publisher.arn
}
