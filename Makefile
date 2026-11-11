GRADLE   := ./gradlew
JAR      := app/build/libs/app.jar
ENV_FILE ?= $(HOME)/.config/preferences-service/oracle.env
# The application login's password, read from the local password file into the app's environment only.
DB_ENV    = PREF_DB_PASSWORD="$$(sed -n 's/^APP_USER_PASSWORD=//p' $(ENV_FILE))"

.DEFAULT_GOAL := help
.PHONY: help build test run jar run-jar clean deps db-start db-stop db-status db-remove

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

build: ## Compile, run tests, and build the executable jar (tests need Docker)
	$(GRADLE) build

test: ## Run all tests (single test: make test TEST=org.example.GreetingControllerTest)
	$(GRADLE) :app:test $(if $(TEST),--tests '$(TEST)')

run: db-start ## Start the database if needed, then run the service from source on port 8080
	@$(DB_ENV) $(GRADLE) :app:bootRun

jar: ## Build the executable jar without running tests
	$(GRADLE) :app:bootJar

run-jar: jar db-start ## Build the jar, start the database if needed, and run the jar
	@$(DB_ENV) java -jar $(JAR)

clean: ## Delete build outputs
	$(GRADLE) clean

deps: ## Show the runtime dependency tree
	$(GRADLE) :app:dependencies --configuration runtimeClasspath

db-start: ## Create or start the local Oracle container (127.0.0.1:1525)
	@scripts/oracle-db.sh start

db-stop: ## Stop the local Oracle container (data kept)
	@scripts/oracle-db.sh stop

db-status: ## Show whether the local Oracle container is running
	@scripts/oracle-db.sh status

db-remove: ## Delete the local Oracle container and its data
	@scripts/oracle-db.sh remove
