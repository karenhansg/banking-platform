# Deployment guide

How to set up the AWS infrastructure and the delivery pipeline from scratch, then run the platform day to day. For why it is built this way, see [ARCHITECTURE.md](ARCHITECTURE.md).

1. [Try it locally](#1-try-it-locally-optional) (optional)
2. [Bootstrap the AWS account](#2-bootstrap-the-aws-account)
3. [Publish the first image](#3-publish-the-first-image)
4. [Create the dev environment](#4-create-the-dev-environment)
5. [Deploy and verify dev](#5-deploy-and-verify-dev)
6. [Connect the pipeline](#6-connect-the-pipeline)
7. [Create production](#7-create-production)
8. [Operations](#8-operations)
9. [Tearing down](#9-tearing-down)
10. [Troubleshooting](#10-troubleshooting)

## Prerequisites

| Requirement | Notes |
|---|---|
| AWS account and AWS CLI v2 | Credentials allowed to create VPC, ECS, RDS, IAM, KMS, WAF, Cognito, S3, ECR and CloudWatch resources. A dedicated sandbox account with administrator access is simplest. |
| Terraform 1.10 or later | Tested with 1.15. |
| Docker with Buildx | Builds the image; also runs the local environment and the integration tests. |
| Java 21 | Only needed to run the tests outside Docker. |
| `jq` and `curl` | Used by the scripts. |
| A GitHub repository and the `gh` CLI | For the pipeline (step 6). `gh` is optional; everything can be done in the GitHub web UI. |

The examples use `us-east-1` and the repository name `your-org/banking-platform`; substitute your own consistently. Creating an environment takes about 15–20 minutes, mostly for the database. Running both environments costs money; see the [cost estimate](ARCHITECTURE.md#11-cost) and [tearing down](#9-tearing-down).

All commands start from the repository root unless a step says otherwise.

## 1. Try it locally (optional)

```bash
make test   # unit and integration tests (needs Docker)
make up     # PostgreSQL, a mock OAuth2 server and the API on http://localhost:8080
```

The [README](../README.md#setup-and-run) shows example requests. `make down` stops everything and deletes the local data.

## 2. Bootstrap the AWS account

This creates what every environment in the account shares: the encrypted Terraform state bucket, the image repository, the GitHub identity provider and the role the pipeline uses to push images. It is done once per account.

```bash
cd infra/bootstrap
terraform init
terraform apply -var "region=us-east-1" -var "github_repository=your-org/banking-platform"
terraform output
```

If the account already has a GitHub Actions identity provider (there can only be one), add `-var "create_github_oidc_provider=false"`.

The bootstrap's own state is kept locally in `infra/bootstrap/terraform.tfstate` (git-ignored). Keep it safe, or move it into the new bucket by adding a `backend "s3"` block like the environments' and running `terraform init -migrate-state`.

## 3. Publish the first image

An environment's task definitions refer to an image, so one must exist before the environment is created. Later images are published by the pipeline.

```bash
cd ../..   # back to the repository root
export AWS_REGION=us-east-1
export ECR_REPOSITORY_URL=$(terraform -chdir=infra/bootstrap output -raw ecr_repository_url)
export IMAGE_TAG=$(git rev-parse HEAD 2>/dev/null || date +%Y%m%d%H%M%S)

aws ecr get-login-password --region "$AWS_REGION" \
  | docker login --username AWS --password-stdin "${ECR_REPOSITORY_URL%%/*}"

docker buildx build --platform linux/arm64 --provenance=false \
  --tag "$ECR_REPOSITORY_URL:$IMAGE_TAG" --push .
```

The tasks run on ARM64 (Graviton), so the image must be built for `linux/arm64`. Tags are immutable: publishing again requires a new tag.

## 4. Create the dev environment

```bash
cd infra/environments/dev
cp backend.hcl.example backend.hcl
cp terraform.tfvars.example terraform.tfvars
```

Fill in both files (both are git-ignored):

- `backend.hcl`: `bucket` and `kms_key_id` from the bootstrap outputs `terraform_state_bucket` and `terraform_state_kms_key_arn`, and the region.
- `terraform.tfvars`: the region and repository name; `github_oidc_provider_arn`, `ecr_repository_url` and `ecr_repository_arn` from the bootstrap outputs; `image_tag` set to the `$IMAGE_TAG` published in step 3. Optionally set `business_time_zone` (for example `Asia/Singapore`), `alarm_email`, and `domain_name` with `route53_zone_id` for a trusted certificate.

Then create the environment:

```bash
terraform init -backend-config=backend.hcl
terraform plan -out=tfplan
terraform apply tfplan
terraform output
```

Notes:

- Without `domain_name`, the API is served on the load balancer's DNS name with a self-signed certificate, so `curl` needs `-k`.
- If you set `alarm_email`, AWS sends a subscription confirmation that must be accepted.
- The API service is created with no running tasks. It cannot start until the first deployment has run the database migrations, which also create the application's database role.

## 5. Deploy and verify dev

Still in `infra/environments/dev`, export the deployment settings from the Terraform outputs and deploy the image published in step 3:

```bash
eval "$(terraform output -json github_environment_variables \
  | jq -r 'to_entries[] | "export \(.key)=\(.value)"')"
export IMAGE="$ECR_REPOSITORY_URL:$IMAGE_TAG"

../../../scripts/deploy.sh
```

The script runs the migrations as a one-off task, starts the service at its minimum capacity, and waits until the rollout is complete (typically 3–5 minutes). It ends with `==> Deployed <image>`.

Next, run the smoke test with the `channel` client, whose secret is read from Cognito:

```bash
export TOKEN_URL=$(terraform output -raw token_endpoint)
export CLIENT_ID=$(terraform output -json client_ids | jq -r .channel)
export CLIENT_SECRET=$(aws cognito-idp describe-user-pool-client \
  --user-pool-id "$(terraform output -raw user_pool_id)" --client-id "$CLIENT_ID" \
  --query UserPoolClient.ClientSecret --output text)

../../../scripts/smoke-test.sh
```

It checks that a request without a token is rejected, then opens an account, deposits, withdraws and reads the balance. To call the API yourself with the same variables:

```bash
TOKEN=$(../../../scripts/get-token.sh)

curl -sk -X POST "$API_BASE_URL/api/v1/accounts" \
  -H "Authorization: Bearer $TOKEN" -H "Content-Type: application/json" \
  -d '{"ownerName": "Ada Lovelace"}'
```

For the read-only `reporting` client, request only the scopes it holds: `SCOPE="banking-api/accounts.read banking-api/payments.read"`.

To explore the environment by hand, import [`postman/banking-api.postman_collection.json`](../postman/banking-api.postman_collection.json) together with `postman/aws.postman_environment.json`, and fill in `baseUrl`, `tokenUrl`, `clientId` and `clientSecret` from the outputs above.

Useful places to look:

- Dashboard: `terraform output dashboard_url`
- Application logs: `aws logs tail /ecs/banking-dev/api --follow --format short`

## 6. Connect the pipeline

The pipeline ([`.github/workflows`](../.github/workflows)) needs a few GitHub variables and two GitHub environments. Configure them before the first push, so that the first run on `main` can publish and deploy. The commands below use `gh` from the repository root; the same settings are under **Settings → Secrets and variables → Actions** and **Settings → Environments** in the GitHub UI.

**Create the repository** (the name must match `github_repository` used in steps 2 and 4):

```bash
git init -b main
git add .
git commit -m "Banking platform"
gh repo create your-org/banking-platform --private --source . --remote origin
```

**Repository variables** used to publish images:

```bash
gh variable set AWS_REGION --body "us-east-1"
gh variable set ECR_REPOSITORY_URL --body "$(terraform -chdir=infra/bootstrap output -raw ecr_repository_url)"
gh variable set AWS_IMAGE_PUBLISHER_ROLE_ARN \
  --body "$(terraform -chdir=infra/bootstrap output -raw github_image_publisher_role_arn)"
```

**Environments.** Create `dev` and `prod`, then copy each environment's Terraform output into its GitHub environment variables. The deploy role of each AWS environment trusts only jobs running in the GitHub environment of the same name.

```bash
for environment in dev prod; do
  gh api --method PUT "repos/{owner}/{repo}/environments/$environment" > /dev/null
done

terraform -chdir=infra/environments/dev output -json github_environment_variables \
  | jq -r 'to_entries[] | "\(.key)\t\(.value)"' \
  | while IFS=$'\t' read -r name value; do gh variable set "$name" --env dev --body "$value"; done
```

In **Settings → Environments → prod**, add required reviewers and restrict deployments to the `main` branch. The prod variables are set in step 7.

Optionally, let the dev smoke test run its authenticated checks too:

```bash
gh variable set SMOKE_TEST_TOKEN_URL --env dev --body "$TOKEN_URL"
gh variable set SMOKE_TEST_CLIENT_ID --env dev --body "$CLIENT_ID"
gh secret set SMOKE_TEST_CLIENT_SECRET --env dev --body "$CLIENT_SECRET"
```

**Push:**

```bash
git push -u origin main
```

From now on, pull requests run the tests, the infrastructure checks and the image build and scan. Merges to `main` also publish the image, deploy it to dev, and then wait for a reviewer to approve the prod deployment. Until prod exists (step 7), leave that approval pending or reject it.

## 7. Create production

Repeat steps 4 and 5 in `infra/environments/prod`. It has its own state key (see `backend.tf`) and production sizing. Recommended settings in `terraform.tfvars`:

- `domain_name` and `route53_zone_id`, for a trusted certificate
- `alarm_email`
- `business_time_zone`: the bank's time zone, which defines when the daily withdrawal limit resets

Instead of running `scripts/deploy.sh` for the first prod deployment, you can approve the pending prod job of the latest pipeline run after setting the prod variables:

```bash
terraform -chdir=infra/environments/prod output -json github_environment_variables \
  | jq -r 'to_entries[] | "\(.key)\t\(.value)"' \
  | while IFS=$'\t' read -r name value; do gh variable set "$name" --env prod --body "$value"; done
```

Production has deletion protection enabled on the database, the load balancer and the user pool.

## 8. Operations

**Release.** Merge to `main`. The pipeline deploys the commit's image to dev and, once approved, the same image to prod.

**Roll back.** Redeploy an earlier commit's image with that environment's variables exported (as in step 5):

```bash
IMAGE="$ECR_REPOSITORY_URL:<earlier-commit-sha>" scripts/deploy.sh
```

Migrations are not reversed. This is safe as long as migrations stay compatible with the previous release ([expand and contract](ARCHITECTURE.md#7-delivery-pipeline)).

**Change infrastructure.** Edit the Terraform, open a pull request (the pipeline validates it), then run `terraform plan` and `terraform apply` in the environment's directory. Changes to task definitions, such as environment variables, CPU or memory, take effect at the next deployment.

**Change a schema.** Add a new `src/main/resources/db/migration/V<n>__<description>.sql`, and grant `banking_app` the privileges it needs on any new table. Never edit a migration that has already been applied.

**Investigate.** Error responses include a `requestId`. Find every log line for that request with CloudWatch Logs Insights on `/ecs/banking-<environment>/api`:

```
fields @timestamp, level, logger_name, message
| filter requestId = "<request id>"
| sort @timestamp asc
```

Other log groups: `/ecs/banking-<environment>/migrate`, `aws-waf-logs-banking-<environment>`, `/aws/rds/instance/banking-<environment>/postgresql`, and `/aws/vpc/banking-<environment>/flow-logs`.

**Scale.** Autoscaling keeps the task count between `api_min_capacity` and `api_max_capacity`, which are set in `infra/environments/<environment>/main.tf`.

**Credentials.** The database master password is rotated by RDS automatically; the migration task reads the current value each time it starts. API clients and their scopes are defined by the `clients` variable of [`infra/modules/auth`](../infra/modules/auth).

## 9. Tearing down

**dev:**

```bash
cd infra/environments/dev
terraform destroy
```

**prod:** take a manual database snapshot if the data matters. Then set `protect_from_deletion = false` in `infra/environments/prod/main.tf`, run `terraform apply` and then `terraform destroy`.

**bootstrap**, after every environment is gone: delete the images in the ECR repository, remove `prevent_destroy` from the state bucket and empty it (including object versions), then run `terraform destroy` in `infra/bootstrap`.

## 10. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `deploy.sh` stops with "migrations failed" | Read the migration logs (`aws logs tail /ecs/banking-<environment>/migrate --since 1h`). Nothing was rolled out. |
| Tasks stop immediately with `exec format error` | The image was not built for ARM64. Rebuild with `--platform linux/arm64`. |
| API logs show `PAM authentication failed for user "banking_app"` | The migrations, which create the role, have not run against this database. Run `scripts/deploy.sh`. |
| `401` with a Cognito token | The token is expired or from another user pool. Its `iss` claim must equal `JWT_ISSUER_URI` in the task definition. |
| `403` | The client lacks the operation's scope; `reporting` is read-only. |
| `invalid_scope` from the token endpoint | A scope was requested that the client is not allowed; set `SCOPE`. |
| `404` from the load balancer | Only paths under `/api/` are routed to the application. |
| `curl: (60) SSL certificate problem` | The environment has no `domain_name`, so it uses a self-signed certificate. Use `-k`, or configure a domain. |
| `terraform apply` for bootstrap fails creating the identity provider | The account already has one. Use `-var "create_github_oidc_provider=false"`. |
