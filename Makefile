GRADLE := ./gradlew
JAR    := app/build/libs/app.jar

.DEFAULT_GOAL := help
.PHONY: help build test run jar run-jar clean deps

help: ## Show available targets
	@grep -E '^[a-zA-Z_-]+:.*?## ' $(MAKEFILE_LIST) | awk 'BEGIN {FS = ":.*?## "}; {printf "  %-10s %s\n", $$1, $$2}'

build: ## Compile, run tests, and build the executable jar
	$(GRADLE) build

test: ## Run all tests (single test: make test TEST=org.example.GreetingControllerTest)
	$(GRADLE) :app:test $(if $(TEST),--tests '$(TEST)')

run: ## Run the service from source on port 8080
	$(GRADLE) :app:bootRun

jar: ## Build the executable jar without running tests
	$(GRADLE) :app:bootJar

run-jar: jar ## Build and run the packaged jar
	java -jar $(JAR)

clean: ## Delete build outputs
	$(GRADLE) clean

deps: ## Show the runtime dependency tree
	$(GRADLE) :app:dependencies --configuration runtimeClasspath
