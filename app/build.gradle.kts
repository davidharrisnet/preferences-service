plugins {
    java
    alias(libs.plugins.spring.boot)
}

repositories {
    // Use Maven Central for resolving dependencies.
    mavenCentral()
}

dependencies {
    // Spring Boot BOM manages versions of all Spring Boot starters and their transitive dependencies.
    implementation(platform(libs.spring.boot.dependencies))
    testImplementation(platform(libs.spring.boot.dependencies))
    testRuntimeOnly(platform(libs.spring.boot.dependencies))

    implementation(libs.spring.boot.starter.webmvc)
    implementation(libs.spring.boot.starter.actuator)
    // JPA (Hibernate, Spring Data JPA) and JDBC (JdbcTemplate, HikariCP connection pool)
    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.jdbc)
    // Oracle JDBC driver (thin, pure Java, no Oracle client needed); the version comes from the Spring Boot BOM
    runtimeOnly(libs.oracle.ojdbc11)
    // OpenAPI document (/v3/api-docs) and Swagger UI (/swagger-ui.html) for the REST endpoints
    implementation(libs.springdoc.openapi.webmvc.ui)
    implementation(libs.guava)

    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.boot.starter.webmvc.test)
    // @DataJpaTest for the repository tests
    testImplementation(libs.spring.boot.starter.data.jpa.test)
    // Tests that load the full context start a throwaway Oracle container (Docker required)
    testImplementation(libs.spring.boot.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
    testImplementation(libs.testcontainers.oracle.free)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Apply a specific Java toolchain to ease working on different environments.
java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.test {
    useJUnitPlatform()
}
