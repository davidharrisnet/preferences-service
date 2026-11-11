GRADLE   := ./gradlew
JAR      := app/build/libs/app.jar
DB       := scripts/oracle-db.sh
ENV_FILE ?= $(HOME)/.config/preferences-service/oracle.env
# The application login's password, read from the local password file into the app's environment only.
DB_ENV    = PREF_DB_PASSWORD="$$(sed -n 's/^APP_USER_PASSWORD=//p' $(ENV_FILE))"
# Passed to the script through the environment, so SQL with quotes in it arrives unchanged.
export SQL FRUIT

.DEFAULT_GOAL := help
.PHONY: help build test run jar run-jar clean deps \
        db-start db-stop db-restart db-status db-logs db-remove db-recreate \
        db-sql db-query db-tables db-fruits db-users db-retire db-activate db-drop-tables db-check

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-15s %s\n", $$1, $$2}'

build: ## Compile, run tests, and build the executable jar (tests need Docker)
	$(GRADLE) build

test: ## Run all tests (single test: make test TEST=org.example.user.UserSchemaTest)
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

# --- The local Oracle container ------------------------------------------------------------------------------------

db-start: ## Create or start the local Oracle container (127.0.0.1:1525, database PREFERENCES)
	@$(DB) start

db-stop: ## Stop the local Oracle container (data kept)
	@$(DB) stop

db-restart: db-stop db-start ## Stop and start the container (data kept)

db-status: ## Show whether the local Oracle container is running
	@$(DB) status

db-logs: ## Follow the container's log (Ctrl+C to stop)
	@$(DB) logs

db-remove: ## Delete the local Oracle container and ALL its data
	@$(DB) remove

db-recreate: db-remove db-start ## Delete the container and its data, then create a fresh one

# --- The data (database PREFERENCES, schema PREFERENCES; no password needed) ---------------------------------------

db-sql: ## Interactive SQL*Plus in the PREFERENCES database (EXIT to leave)
	@$(DB) sql

db-query: ## Run SQL: make db-query SQL="SELECT * FROM fruit;"  or  make db-query < file.sql
	@$(DB) query "$$SQL"

db-tables: ## List the application's tables with row counts
	@$(DB) tables

db-fruits: ## List the fruit list with active flags
	@$(DB) fruits

db-users: ## List users with their settings and fruit order
	@$(DB) users

db-retire: ## Retire a fruit (hidden from everyone): make db-retire FRUIT=2
	@$(DB) retire "$$FRUIT"

db-activate: ## Bring a retired fruit back: make db-activate FRUIT=2
	@$(DB) activate "$$FRUIT"

db-drop-tables: ## Delete the 4 tables and their data (make db-drop-tables CONFIRM=yes); next start recreates them
	@$(DB) drop-tables $(if $(filter yes,$(CONFIRM)),--yes)

db-check: db-tables db-fruits ## Show tables and fruits (a quick look after a start)
