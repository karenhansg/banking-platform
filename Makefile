.DEFAULT_GOAL := help

INFRA_ROOTS := infra/bootstrap infra/environments/dev infra/environments/prod
MERMAID := docker run --rm -u "$$(id -u):$$(id -g)" -v "$(PWD)/docs:/docs" minlag/mermaid-cli:latest

.PHONY: help test up down logs token seed postman openapi diagrams infra-validate

help: ## List the available targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z_-]+:.*## / {printf "  %-16s %s\n", $$1, $$2}' $(MAKEFILE_LIST)

test: ## Run unit and integration tests and the coverage gate (needs Docker)
	./mvnw -B verify

up: ## Start PostgreSQL, the mock OAuth2 server and the API on http://localhost:8080
	docker compose up --build --detach
	@echo "Waiting for the API..."
	@until curl -sf -o /dev/null http://localhost:8080/actuator/health/readiness; do sleep 2; done
	@echo "API ready: http://localhost:8080/swagger-ui.html (token: make token)"

down: ## Stop the local environment and delete its data
	docker compose down --volumes

logs: ## Follow the API logs
	docker compose logs --follow api

token: ## Print a full-access access token for the local API
	@scripts/get-token.sh

seed: ## Create sample accounts, transactions and payments through the local API
	@scripts/seed-sample-data.sh

postman: ## Run the Postman collection against the local API with newman (needs Node)
	npx --yes newman run postman/banking-api.postman_collection.json -e postman/local.postman_environment.json

openapi: ## Regenerate docs/openapi.json from the running API
	@curl -sf http://localhost:8080/v3/api-docs | jq --sort-keys . > docs/openapi.json && echo "docs/openapi.json"

diagrams: ## Regenerate docs/images/*.png from docs/diagrams/*.mmd (needs Docker)
	@for source in docs/diagrams/*.mmd; do \
		name=$$(basename $$source .mmd); \
		$(MERMAID) -q -i /docs/diagrams/$$name.mmd -o /docs/images/$$name.png -w 1600 -b white; \
		echo "docs/images/$$name.png"; \
	done

infra-validate: ## Check formatting and validate every Terraform root module
	terraform fmt -check -recursive infra
	@for root in $(INFRA_ROOTS); do \
		echo "== $$root"; \
		terraform -chdir=$$root init -backend=false -input=false > /dev/null && terraform -chdir=$$root validate || exit 1; \
	done
