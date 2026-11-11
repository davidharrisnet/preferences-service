# Adding Spring Data JPA, JDBC and an Oracle connection

These steps connect a Spring Boot service to an Oracle database through JPA (Hibernate) and JDBC. The starting point is the Spring Boot service that [SERVICE.md](SERVICE.md) describes. This repository went through these exact steps. The connection settings follow those of the `master-antique-repair-claude` project (`model/oracle`).

**Target stack:** Spring Boot 4.1.2, Oracle AI Database 26ai Free (`gvenzl/oracle-free:23.26.3-faststart`, reports version 23.26.3) in Docker, Oracle JDBC driver `ojdbc11` 23.26.3.0.0, HikariCP, Testcontainers 2.0.5.

**Requirements:** Java 21, Docker, `openssl` (to generate passwords).

Run all commands from the repository root.

## 1. Update the version catalog (`gradle/libs.versions.toml`)

### 1.1 Add the database libraries

**1.1.1** Under `[libraries]`, add the JPA and JDBC starters and the Oracle driver. The Spring Boot BOM supplies their versions:

```toml
spring-boot-starter-data-jpa = { module = "org.springframework.boot:spring-boot-starter-data-jpa" }
spring-boot-starter-jdbc = { module = "org.springframework.boot:spring-boot-starter-jdbc" }
oracle-ojdbc11 = { module = "com.oracle.database.jdbc:ojdbc11" }
```

`spring-boot-starter-data-jpa` already includes `spring-boot-starter-jdbc`. Declaring JDBC as well makes it visible that the service uses `JdbcTemplate` directly.

### 1.2 Add the test libraries

**1.2.1** Add Spring Boot's Testcontainers support and the Oracle Free module:

```toml
spring-boot-testcontainers = { module = "org.springframework.boot:spring-boot-testcontainers" }
testcontainers-junit-jupiter = { module = "org.testcontainers:testcontainers-junit-jupiter" }
testcontainers-oracle-free = { module = "org.testcontainers:testcontainers-oracle-free" }
```

The artifact names have the `testcontainers-` prefix because Testcontainers 2.x renamed its modules. Testcontainers 1.x called them `junit-jupiter` and `oracle-free`.

## 2. Update the build script (`app/build.gradle.kts`)

### 2.1 Add the main dependencies

**2.1.1** Add JPA, JDBC and the Oracle driver:

```kotlin
// JPA (Hibernate, Spring Data JPA) and JDBC (JdbcTemplate, HikariCP connection pool)
implementation(libs.spring.boot.starter.data.jpa)
implementation(libs.spring.boot.starter.jdbc)
// Oracle JDBC driver (thin, pure Java, no Oracle client needed); the version comes from the Spring Boot BOM
runtimeOnly(libs.oracle.ojdbc11)
```

The driver is `runtimeOnly` because the code uses only JDBC and JPA interfaces, never Oracle classes.

### 2.2 Add the test dependencies

**2.2.1** Add:

```kotlin
testImplementation(libs.spring.boot.testcontainers)
testImplementation(libs.testcontainers.junit.jupiter)
testImplementation(libs.testcontainers.oracle.free)
```

## 3. Add the local database script (`scripts/oracle-db.sh`)

### 3.1 Create the script

**3.1.1** Create `scripts/oracle-db.sh` with four commands: `start`, `stop`, `status` and `remove`. Make it executable with `chmod +x scripts/oracle-db.sh`.

**3.1.2** Use these names and settings. Each one can be overridden with the environment variable in brackets:

| Setting | Value |
|---|---|
| Container | `preferences-oracle` (`PREF_DB_CONTAINER`) |
| Data volume | `preferences-oracle-data`, mounted at `/opt/oracle/oradata` |
| Image | `gvenzl/oracle-free:23.26.3-faststart` (`PREF_DB_IMAGE`) |
| Published port | `127.0.0.1:1525` → `1521` (`PREF_DB_PORT`) |
| Password file | `~/.config/preferences-service/oracle.env` (`PREF_ENV_FILE`) |

Port 1525 leaves 1521 free for a locally installed Oracle, and 1522-1524 free for the antique-repair containers. Publishing on `127.0.0.1` keeps the database off the network.

### 3.2 Keep the passwords out of the repository

**3.2.1** On first use, have the script create the password file with mode 600, in a folder with mode 700:

```bash
(
    umask 077
    printf 'ORACLE_PASSWORD=S%s\nAPP_USER=preferences\nAPP_USER_PASSWORD=P%s\n' \
        "$(openssl rand -hex 16)" "$(openssl rand -hex 16)" > "$ENV_FILE"
)
```

`ORACLE_PASSWORD` is the SYS/SYSTEM password, and `APP_USER_PASSWORD` is the application login's. The passwords contain only letters and digits and start with a letter, so they are valid unquoted Oracle passwords.

**3.2.2** Never print the file, put a password on a command line, or commit one.

### 3.3 Create and start the container

**3.3.1** For `start`, create the container on the first run and start it on later runs:

```bash
docker run -d --name "$CONTAINER" \
    -p "127.0.0.1:$PORT:1521" \
    --env-file "$ENV_FILE" \
    -e "ORACLE_DATABASE=PREFERENCES" \
    -v "$VOLUME:/opt/oracle/oradata" \
    "$IMAGE"
```

`--env-file` hands the passwords to Docker without putting them on the command line. `ORACLE_DATABASE` isn't secret, so it can go on the command line. On its first start the image creates a pluggable database named `PREFERENCES` and, inside it, the login `APP_USER` (`preferences`), which owns a schema of the same name. The service's tables live only there.

The image also always creates its default pluggable database, `FREEPDB1`, with a copy of the same login. The service doesn't use it.

**3.3.2** Wait until `docker logs` shows `DATABASE IS READY TO USE!`. The first start takes about a minute.

### 3.4 Add the other commands

**3.4.1** Make `stop` run `docker stop`, which keeps the data.

**3.4.2** Make `status` report whether the container is running, stopped or missing.

**3.4.3** Make `remove` run `docker rm -f` and `docker volume rm`, which deletes the container and its data. Keep the password file, so a new container gets the same passwords.

## 4. Configure the connection (`app/src/main/resources/application.properties`)

### 4.1 Add the data source

**4.1.1** Build the URL from environment variables, with defaults for the local container:

```properties
spring.datasource.url=jdbc:oracle:thin:@//${PREF_DB_HOST:localhost}:${PREF_DB_PORT:1525}/${PREF_DB_SERVICE:PREFERENCES}
spring.datasource.username=${PREF_DB_USER:preferences}
spring.datasource.password=${PREF_DB_PASSWORD}
```

`PREFERENCES` is the pluggable database's service name, so the URL uses the `@//host:port/service` form, not `@host:port:SID`.

**4.1.2** Give the password no default. The application must get it from `PREF_DB_PASSWORD`, and it never goes in a repository file.

### 4.2 Configure the connection pool

**4.2.1** Add the HikariCP settings:

```properties
spring.datasource.hikari.pool-name=preferences-pool
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.connection-timeout=10000
```

**4.2.2** Leave out antique-repair's `connection-init-sql=ALTER SESSION SET CURRENT_SCHEMA = ...`. That project's login reads another user's schema; here the `preferences` login owns the schema it uses.

### 4.3 Configure JPA and Hibernate

**4.3.1** Add:

```properties
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
spring.jpa.properties.hibernate.jdbc.fetch_size=100
spring.sql.init.mode=never
```

- `validate` makes Hibernate check each entity against its table and never create or change tables.
- `open-in-view=false` keeps database work inside service methods, not in the web layer.
- `fetch_size=100` replaces the Oracle driver's default of 10 rows per round trip, which Hibernate warns about.
- `spring.sql.init.mode=never` stops Spring from running `schema.sql` or `data.sql` scripts.

**4.3.2** To see the SQL Hibernate sends, add `logging.level.org.hibernate.SQL=debug`.

## 5. Update the Makefile

### 5.1 Pass the password to the application

**5.1.1** Read the password from the password file into the application's environment only:

```make
ENV_FILE ?= $(HOME)/.config/preferences-service/oracle.env
DB_ENV    = PREF_DB_PASSWORD="$$(sed -n 's/^APP_USER_PASSWORD=//p' $(ENV_FILE))"
```

**5.1.2** Make `run` and `run-jar` depend on `db-start`, and prefix their commands with `@$(DB_ENV)`. The `@` keeps make from echoing the command.

```make
run: db-start
	@$(DB_ENV) $(GRADLE) :app:bootRun
```

### 5.2 Add the database targets

**5.2.1** Add `db-start`, `db-stop`, `db-status` and `db-remove`. Each one calls `scripts/oracle-db.sh` with the matching command.

## 6. Test against a throwaway Oracle (`app/src/test/java/org/example/`)

### 6.1 Add `TestcontainersConfiguration.java`

**6.1.1** Create a test configuration that starts an Oracle container:

```java
@TestConfiguration(proxyBeanMethods = false)
public class TestcontainersConfiguration {

    static final DockerImageName ORACLE_IMAGE = DockerImageName.parse("gvenzl/oracle-free:23.26.3-faststart");

    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        return new OracleContainer(ORACLE_IMAGE).withDatabaseName("preferences").withUsername("preferences");
    }
}
```

`withDatabaseName` and `withUsername` give the test container the same database and login names as the local one.

`@ServiceConnection` points `spring.datasource.*` at the container, so tests use neither the local database nor its password. `OracleContainer` comes from `org.testcontainers.oracle`. Using the same image as the local script means the tests run against the same Oracle version.

### 6.2 Update `AppTest.java`

**6.2.1** Add `@Import(TestcontainersConfiguration.class)` to the `@SpringBootTest` class. Without it the context can't start, because `PREF_DB_PASSWORD` isn't set.

**6.2.2** Add a test that checks the connection:

```java
@Test
void connectsToOracle() throws Exception {
    try (var connection = dataSource.getConnection()) {
        assertThat(connection.getMetaData().getDatabaseProductName()).isEqualTo("Oracle");
    }
    assertThat(jdbc.queryForObject("SELECT 1 FROM dual", Integer.class)).isEqualTo(1);
}
```

### 6.3 Keep the controller test as it is

**6.3.1** Leave `GreetingControllerTest` unchanged. `@WebMvcTest` loads only the web layer, so it needs no database.

## 7. Verify

### 7.1 Run the tests

**7.1.1** Run `./gradlew test`. Docker must be running. The first run pulls the image, and each run takes about a minute while the container starts. Expect `AppTest` (2 tests) and `GreetingControllerTest` (1 test) to pass.

### 7.2 Run the service on the local database

**7.2.1** Run `make run`. It creates or starts `preferences-oracle`, then starts the service with the password from the password file.

**7.2.2** Check the log for these lines:

```
preferences-pool - Added connection oracle.jdbc.driver.T4CConnection@...
	Database version: 23.26.3
Started App in ... seconds
```

**7.2.3** Call `GET http://localhost:8080/actuator/health`. It returns `{"status":"UP",...}` only while the database is reachable, because the health check includes the database.

### 7.3 Know the common errors

| Error | Cause | Fix |
|---|---|---|
| `Could not resolve placeholder 'PREF_DB_PASSWORD'` | The service was started without the password | Use `make run` or `make run-jar`, or export `PREF_DB_PASSWORD` yourself |
| `ORA-12541` (no listener) | The container is stopped or still starting | `make db-start` |
| `ORA-01017` (invalid username/password) | The password file was changed after the container was created | Restore the file, or `make db-remove && make db-start` (deletes the data) |
| `ORA-12514` (unknown service) | Wrong service name, or a container created before the `PREFERENCES` database existed | The service is `PREFERENCES`; recreate an old container with `make db-remove && make db-start` (deletes its data) |
| Tests fail with `Could not find a valid Docker environment` | Docker isn't running | Start Docker |

## 8. Next steps

### 8.1 Add the first entity

**8.1.1** Create the table before the entity. With `ddl-auto=validate`, Hibernate refuses to start if a table or column an entity needs is missing.

**8.1.2** Choose how the schema is managed: a `schema.sql` with `spring.sql.init.mode=always`, or a migration tool such as Flyway.
