#!/usr/bin/env bash
#
# Deploys an image that is already in ECR to one environment:
#   1. runs the database migrations as a one-off ECS task and waits for it to succeed
#   2. rolls the API service onto a task definition revision that uses the image
#   3. waits for ECS to report the rollout complete (the circuit breaker rolls back a failed rollout)
#
# The pipeline runs it for each environment; it runs the same way from a workstation:
#   IMAGE=<registry>/banking-api:<git-sha> ECS_CLUSTER=banking-dev ECS_SERVICE=api \
#   API_TASK_FAMILY=banking-dev-api MIGRATION_TASK_FAMILY=banking-dev-migrate scripts/deploy.sh
#
# Requires the AWS CLI v2 and jq, with credentials for the environment's deploy role.

set -euo pipefail

: "${IMAGE:?IMAGE must be set, e.g. 123456789012.dkr.ecr.us-east-1.amazonaws.com/banking-api:<git-sha>}"
: "${ECS_CLUSTER:?ECS_CLUSTER must be set}"
: "${ECS_SERVICE:?ECS_SERVICE must be set}"
: "${API_TASK_FAMILY:?API_TASK_FAMILY must be set}"
: "${MIGRATION_TASK_FAMILY:?MIGRATION_TASK_FAMILY must be set}"

workdir=$(mktemp -d)
trap 'rm -rf "$workdir"' EXIT

log() {
  printf '\n==> %s\n' "$*"
}

fail() {
  printf 'ERROR: %s\n' "$*" >&2
  exit 1
}

# Registers a copy of the family's latest task definition with the container image replaced; prints the new ARN.
register_revision() {
  local family=$1
  aws ecs describe-task-definition --task-definition "$family" --query taskDefinition --output json \
    | jq --arg image "$IMAGE" '
        .containerDefinitions[0].image = $image
        | del(.taskDefinitionArn, .revision, .status, .requiresAttributes, .compatibilities,
              .registeredAt, .registeredBy, .deregisteredAt)' \
    > "$workdir/$family.json"
  aws ecs register-task-definition --cli-input-json "file://$workdir/$family.json" \
    --query taskDefinition.taskDefinitionArn --output text
}

repository_name=${IMAGE%:*}
repository_name=${repository_name#*/}
image_tag=${IMAGE##*:}

log "Checking that $IMAGE exists"
aws ecr describe-images --repository-name "$repository_name" --image-ids imageTag="$image_tag" > /dev/null \
  || fail "image $IMAGE not found"

# --- 1. Migrations -----------------------------------------------------------------------------------------------

log "Running database migrations"
migration_task_definition=$(register_revision "$MIGRATION_TASK_FAMILY")
# The migration task runs in the same subnets and security group as the API, which is what the database admits.
network_configuration=$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
  --query 'services[0].networkConfiguration' --output json)

task_arn=$(aws ecs run-task \
  --cluster "$ECS_CLUSTER" \
  --task-definition "$migration_task_definition" \
  --launch-type FARGATE \
  --network-configuration "$network_configuration" \
  --started-by "deploy-${image_tag:0:40}" \
  --query 'tasks[0].taskArn' --output text)
[[ "$task_arn" == arn:* ]] || fail "could not start the migration task"
echo "Migration task: $task_arn"

aws ecs wait tasks-stopped --cluster "$ECS_CLUSTER" --tasks "$task_arn"
read -r exit_code stopped_reason < <(aws ecs describe-tasks --cluster "$ECS_CLUSTER" --tasks "$task_arn" \
  --query 'tasks[0].[containers[0].exitCode, stoppedReason]' --output text)
if [[ "$exit_code" != "0" ]]; then
  fail "migrations failed (exit code $exit_code: $stopped_reason). See the CloudWatch log group /ecs/$ECS_CLUSTER/migrate"
fi
echo "Migrations applied"

# --- 2. Roll out the API -----------------------------------------------------------------------------------------

log "Deploying $IMAGE to service $ECS_SERVICE"
api_task_definition=$(register_revision "$API_TASK_FAMILY")
update_arguments=(--cluster "$ECS_CLUSTER" --service "$ECS_SERVICE" --task-definition "$api_task_definition")

# The service is created with no tasks, because it cannot start before the first migration. On the first
# deployment, start it at the minimum capacity configured for autoscaling.
desired_count=$(aws ecs describe-services --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
  --query 'services[0].desiredCount' --output text)
if [[ "$desired_count" == "0" ]]; then
  minimum_capacity=$(aws application-autoscaling describe-scalable-targets --service-namespace ecs \
    --resource-ids "service/$ECS_CLUSTER/$ECS_SERVICE" --query 'ScalableTargets[0].MinCapacity' --output text)
  [[ "$minimum_capacity" =~ ^[0-9]+$ ]] || minimum_capacity=2
  echo "First deployment: starting $minimum_capacity tasks"
  update_arguments+=(--desired-count "$minimum_capacity")
fi

aws ecs update-service "${update_arguments[@]}" > /dev/null

# --- 3. Wait for the outcome -------------------------------------------------------------------------------------

log "Waiting for the rollout to finish"
# services-stable also succeeds after a failed rollout has been rolled back, so check which revision is running.
aws ecs wait services-stable --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE"
# shellcheck disable=SC2016 # the backticks are a JMESPath literal, not shell command substitution
read -r rollout_state running_task_definition < <(aws ecs describe-services \
  --cluster "$ECS_CLUSTER" --services "$ECS_SERVICE" \
  --query 'services[0].deployments[?status==`PRIMARY`] | [0].[rolloutState, taskDefinition]' --output text)
if [[ "$rollout_state" != "COMPLETED" || "$running_task_definition" != "$api_task_definition" ]]; then
  fail "rollout did not complete (state: $rollout_state, running: $running_task_definition)"
fi

log "Deployed $IMAGE"
