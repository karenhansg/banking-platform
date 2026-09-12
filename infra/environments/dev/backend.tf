terraform {
  backend "s3" {
    # bucket, region and kms_key_id are supplied at init: terraform init -backend-config=backend.hcl
    key          = "banking/dev/terraform.tfstate"
    encrypt      = true
    use_lockfile = true
  }
}
