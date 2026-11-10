# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

See also [SERVICE.md](SERVICE.md), which records the conversion from the `gradle init` scaffold to Spring Boot.

## Project state

`preferences-service` is a Spring Boot 4.1 web service (Spring MVC on embedded Tomcat, Java 21) in a single Gradle subproject, `app`. The entry point is `org.example.App` (`@SpringBootApplication`). So far the only endpoints are `GET /api/greeting` (`GreetingController`) and the actuator's `/actuator/health` and `/actuator/info`. No preferences logic exists yet.

## Commands

Always use the wrapper from the repo root:

- `./gradlew build`: compile, test, and build the executable jar `app/build/libs/app.jar`
- `./gradlew test`: run all tests
- `./gradlew :app:test --tests org.example.GreetingControllerTest`: run one test class
- `./gradlew :app:test --tests 'org.example.GreetingControllerTest.greetingEndpointReturnsGreeting'`: run one test method
- `./gradlew :app:bootRun`: run the service on port 8080

The root `Makefile` wraps these commands: `make build`, `make test`, `make test TEST=<class or method>`, `make run`, `make jar`, `make run-jar`, `make clean` and `make deps`. Run `make` with no target to list them.

No linter or formatter is configured.

## Build layout

- `app/build.gradle.kts` applies `java` and `org.springframework.boot`. It does **not** use the `application` or `io.spring.dependency-management` plugins. Instead it imports the Spring Boot BOM with `platform(libs.spring.boot.dependencies)`.
- All dependency coordinates live in the version catalog `gradle/libs.versions.toml`. The Spring Boot version is set once there, and Spring starters are declared there without versions because the BOM manages them. Add new dependencies to the catalog and reference them as `libs.<name>`.
- `settings.gradle.kts` applies the foojay resolver, so the Java 21 toolchain is downloaded automatically if it's missing. Keep the toolchain at 21.
- `gradle.properties` turns on the configuration cache, so any custom build logic must work with it.
- Runtime configuration is in `app/src/main/resources/application.properties`. Only the `health` and `info` actuator endpoints are exposed over HTTP.

## Testing

- Tests use **JUnit 5** (Jupiter) on the JUnit Platform. JUnit 4 has been removed.
- Use `@WebMvcTest` with MockMvc for controller slice tests. In Spring Boot 4 the annotation lives in `org.springframework.boot.webmvc.test.autoconfigure` and comes from `spring-boot-starter-webmvc-test`. Use `@SpringBootTest` for full-context tests.

## Conventions

- Keep code under the `org.example` package unless a task explicitly calls for a wider refactor. `App` sits at the package root, so component scanning finds beans in subpackages.
- Add or update tests in `app/src/test/java` alongside behavioural changes.
- Prefer small, targeted edits in `app`. Don't add new build systems or frameworks, or restructure the multi-project layout, without a clear reason.
