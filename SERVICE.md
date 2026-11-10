# Converting a `gradle init` Java application to Spring Boot

These steps turn the Java application that `gradle init` generates into a Spring Boot web service. The starting point is the single-subproject layout `gradle init` produces: an `app` subproject, a version catalog, JUnit 4 tests, and `org.example.App` as the main class. This repository went through these exact steps.

**Target stack:** Spring Boot 4.1.2 (Spring MVC on embedded Tomcat), Java 21, Gradle 9.4.1 (wrapper), JUnit 5.

Run all commands from the repository root.

## 1. Update the version catalog (`gradle/libs.versions.toml`)

### 1.1 Add the Spring Boot version

**1.1.1** Under `[versions]`, add `spring-boot = "4.1.2"`. This is the only place the Spring Boot version is set.

**1.1.2** Remove `junit = "4.13.2"`. Spring Boot's test support is built on JUnit 5 (Jupiter).

**1.1.3** Keep `guava`. Nothing uses it yet, but it is an existing dependency.

### 1.2 Add the libraries

**1.2.1** Add the Spring Boot BOM, pinned to the version from step 1.1.1:

```toml
spring-boot-dependencies = { module = "org.springframework.boot:spring-boot-dependencies", version.ref = "spring-boot" }
```

**1.2.2** Add the starters and the JUnit launcher without versions. The BOM supplies their versions:

```toml
spring-boot-starter-webmvc = { module = "org.springframework.boot:spring-boot-starter-webmvc" }
spring-boot-starter-actuator = { module = "org.springframework.boot:spring-boot-starter-actuator" }
spring-boot-starter-test = { module = "org.springframework.boot:spring-boot-starter-test" }
spring-boot-starter-webmvc-test = { module = "org.springframework.boot:spring-boot-starter-webmvc-test" }
junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher" }
```

**1.2.3** Remove the `junit` library entry.

### 1.3 Add the Spring Boot plugin

**1.3.1** Add a `[plugins]` section:

```toml
[plugins]
spring-boot = { id = "org.springframework.boot", version.ref = "spring-boot" }
```

## 2. Update the build script (`app/build.gradle.kts`)

### 2.1 Replace the plugins

**2.1.1** Remove the `application` plugin.

**2.1.2** Add `java` and the Spring Boot plugin:

```kotlin
plugins {
    java
    alias(libs.plugins.spring.boot)
}
```

The Spring Boot plugin adds the `bootRun` and `bootJar` tasks. `bootJar` builds an executable fat jar.

**2.1.3** Remove the `application { mainClass = "org.example.App" }` block. The Spring Boot plugin finds the `@SpringBootApplication` class on its own.

### 2.2 Import the Spring Boot BOM

**2.2.1** Add the BOM as a platform for every configuration that declares Spring dependencies:

```kotlin
implementation(platform(libs.spring.boot.dependencies))
testImplementation(platform(libs.spring.boot.dependencies))
testRuntimeOnly(platform(libs.spring.boot.dependencies))
```

This replaces the `io.spring.dependency-management` plugin, so no extra plugin is needed.

### 2.3 Replace the dependencies

**2.3.1** Add `implementation(libs.spring.boot.starter.webmvc)` for the REST layer and embedded Tomcat.

**2.3.2** Add `implementation(libs.spring.boot.starter.actuator)` for the health and info endpoints.

**2.3.3** Keep `implementation(libs.guava)`.

**2.3.4** Remove `testImplementation(libs.junit)`.

**2.3.5** Add the test dependencies:

```kotlin
testImplementation(libs.spring.boot.starter.test)
testImplementation(libs.spring.boot.starter.webmvc.test)
testRuntimeOnly(libs.junit.platform.launcher)
```

### 2.4 Run tests on the JUnit Platform

**2.4.1** Add:

```kotlin
tasks.test {
    useJUnitPlatform()
}
```

### 2.5 Keep the rest

**2.5.1** Keep the Java 21 toolchain block and `mavenCentral()`.

## 3. Turn `App` into a Spring Boot application (`app/src/main/java/org/example/App.java`)

### 3.1 Annotate the class

**3.1.1** Add `@SpringBootApplication` to `App`.

**3.1.2** Change `main` to start Spring:

```java
public static void main(String[] args) {
    SpringApplication.run(App.class, args);
}
```

### 3.2 Move the greeting out

**3.2.1** Remove `getGreeting()` from `App`. Step 4 turns it into an HTTP endpoint.

**3.2.2** Keep `App` in the root package, `org.example`. Component scanning starts from `App`'s package, so beans in `org.example` and its subpackages are found.

## 4. Add a REST controller (`app/src/main/java/org/example/GreetingController.java`)

### 4.1 Create the controller

**4.1.1** Add a new `@RestController` mapped at `/api`, with a `GET /api/greeting` method that returns `"Hello World!"`:

```java
@RestController
@RequestMapping("/api")
public class GreetingController {

    @GetMapping("/greeting")
    public String getGreeting() {
        return "Hello World!";
    }
}
```

This keeps the original app's only behaviour, now served over HTTP, and is a pattern for later endpoints to follow.

## 5. Add application configuration (`app/src/main/resources/application.properties`)

### 5.1 Create the file

**5.1.1** Create the `app/src/main/resources` directory. `gradle init` does not generate it.

**5.1.2** Add the properties:

```properties
spring.application.name=preferences-service
server.port=8080
management.endpoints.web.exposure.include=health,info
```

Port 8080 is Spring's default; setting it here makes it easy to find. The last line exposes only the `health` and `info` actuator endpoints over HTTP.

## 6. Migrate the tests (`app/src/test/java/org/example/`)

### 6.1 Rewrite `AppTest.java` for JUnit 5

**6.1.1** Replace the JUnit 4 imports (`org.junit.Test`, `org.junit.Assert`) with `org.junit.jupiter.api.Test`.

**6.1.2** Remove the `appHasAGreeting` test. `App` no longer has a greeting.

**6.1.3** Add a `@SpringBootTest` class with an empty `contextLoads()` test. It checks that the full application context starts.

### 6.2 Add `GreetingControllerTest.java`

**6.2.1** Create a `@WebMvcTest(GreetingController.class)` slice test that injects `MockMvc`.

**6.2.2** Call `GET /api/greeting` and check for status 200 and the body `Hello World!`.

**6.2.3** Import `@WebMvcTest` from `org.springframework.boot.webmvc.test.autoconfigure`. In Spring Boot 4 it lives in that package and comes from `spring-boot-starter-webmvc-test`.

## 7. Update the project documentation

### 7.1 Update `CLAUDE.md`

**7.1.1** Change the commands, build layout and testing notes to match the Spring Boot setup: JUnit 5, `bootRun`, and versions managed by the BOM.

### 7.2 Replace the run command

**7.2.1** Replace any references to `./gradlew :app:run` with `./gradlew :app:bootRun`. The `run` task came from the `application` plugin and no longer exists.

## 8. Keep the unchanged files

### 8.1 Gradle settings

**8.1.1** Keep `settings.gradle.kts`, `gradle.properties` and the Gradle wrapper as they are. The Spring Boot plugin works with the configuration cache, so it stays on.

### 8.2 Project layout

**8.2.1** Keep the `org.example` package and the single `app` subproject.

## 9. Verify the service

### 9.1 Build and test

**9.1.1** Run `./gradlew build`. Both test classes should pass, and `bootJar` should produce `app/build/libs/app.jar`.

### 9.2 Run the service

**9.2.1** Start the service with `./gradlew :app:bootRun`, or run the packaged jar with `java -jar app/build/libs/app.jar`.

**9.2.2** Call the endpoints:

| Method | Path               | Expected response                                    |
|--------|--------------------|------------------------------------------------------|
| GET    | `/api/greeting`    | `Hello World!`                                       |
| GET    | `/actuator/health` | `{"status":"UP",...}`, with liveness and readiness groups |
| GET    | `/actuator/info`   | Application info                                     |
