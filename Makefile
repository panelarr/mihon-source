# Every target wraps Gradle, so `./gradlew <task>` works the same way.
# `make` or `make help` lists the targets.
.DEFAULT_GOAL := help
.PHONY: help setup build test lint fmt check run docker gen clean

GRADLE ?= ./gradlew

help: ## List targets
	@awk 'BEGIN {FS = ":.*## "} /^[a-zA-Z0-9_.-]+:.*## / {printf "  \033[36m%-10s\033[0m %s\n", $$1, $$2}' $(MAKEFILE_LIST)

setup: ## Download Gradle, the JDK 21 toolchain and all dependencies
	$(GRADLE) --version
	$(GRADLE) setup

build: ## Build everything (no tests)
	$(GRADLE) assemble

test: ## Run all tests
	$(GRADLE) test

lint: ## Static analysis and format check (detekt, ktlint)
	$(GRADLE) lint

fmt: ## Format the code (ktlint)
	$(GRADLE) fmt

check: ## Lint plus tests: run before every commit or PR
	$(GRADLE) check

run: ## Run the service locally
	$(GRADLE) :server:run

docker: ## Build the Docker image
	$(GRADLE) docker

gen: ## Regenerate generated sources
	$(GRADLE) gen

clean: ## Remove build outputs
	$(GRADLE) clean
