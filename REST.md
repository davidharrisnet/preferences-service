# Adding the preferences REST API on Oracle

These steps bring the REST API from `user-config-service` (which runs on SQLite) into this project and make it run on Oracle. It covers the repositories, the service, the controller (renamed `PreferencesController`), the error answers, Swagger UI, and the four test classes. This repository went through these exact steps.

**Starting point:** a project that has completed [MODEL.md](MODEL.md):

- the entities `User`, `UserSettings` and `Fruit` in `org.example.user`;
- `schema.sql` and `data.sql` for Oracle;
- the `PREFERENCES` database in the `preferences-oracle` container;
- `TestcontainersConfiguration` in `app/src/test/java/org/example/`.

**Where things come from:** `../user-config-service/user-config-service/src/`. Set a shell variable so the commands below can be pasted as they are:

```bash
UC=../user-config-service/user-config-service/src     # adjust to where user-config-service is
```

**What is copied, and how:**

| user-config-service (`com.example.demo.user`) | This project (`org.example.user`) | Change |
|---|---|---|
| `FruitListOutOfDateException`, `UserAlreadyExistsException`, `UserNotFoundException` | same names | Package only |
| `SettingsSnapshot`, `UserSummary` (records) | same names | Package only |
| `FruitRepository`, `UserRepository`, `UserSettingsRepository` | same names | Package only |
| `UserService` | `UserService` | Package; Lombok removed |
| `UserSettingsController` | **`PreferencesController`** | Package; renamed; Lombok removed |
| test `CreateUserControllerTests` | same name | Package; Oracle Testcontainers instead of SQLite |
| test `FruitOrderControllerTests` | same name | Same, plus `app_user` and a real boolean |
| test `UserSettingsControllerTests` | **`PreferencesControllerTests`** | Same, plus `app_user` |
| test `UserSettingsRepositoryTests` | same name | Same, plus Oracle error codes and number types |
| (not copied) `DemoApplication`, `DemoApplicationTests` | | This project already has `App` and `AppTest` |

**Why the main code barely changes for Oracle:** everything that depended on SQLite lives in the schema, the entities and the tests. MODEL.md already converted the schema and the entities. The service and the controller use JPA and Spring Data only, never SQL, so they run on Oracle unchanged. The Oracle work in this guide is in the tests (section 6).

Run all commands from the repository root.

## 1. Add the dependencies

### 1.1 Update the version catalog (`gradle/libs.versions.toml`)

**1.1.1** Under `[versions]`, add springdoc. The 3.x line is the one for Spring Boot 4, and user-config-service uses the same version:

```toml
# springdoc-openapi 3.x is the line for Spring Boot 4 (same version as user-config-service).
springdoc = "3.2.0"
```

**1.1.2** Under `[libraries]`, add:

```toml
springdoc-openapi-webmvc-ui = { module = "org.springdoc:springdoc-openapi-starter-webmvc-ui", version.ref = "springdoc" }
spring-boot-starter-data-jpa-test = { module = "org.springframework.boot:spring-boot-starter-data-jpa-test" }
```

springdoc needs its own version because it isn't part of the Spring Boot BOM. `spring-boot-starter-data-jpa-test` gets its version from the BOM.

### 1.2 Update the build script (`app/build.gradle.kts`)

**1.2.1** In `dependencies`, after `runtimeOnly(libs.oracle.ojdbc11)`, add:

```kotlin
    // OpenAPI document (/v3/api-docs) and Swagger UI (/swagger-ui.html) for the REST endpoints
    implementation(libs.springdoc.openapi.webmvc.ui)
```

**1.2.2** After `testImplementation(libs.spring.boot.starter.webmvc.test)`, add:

```kotlin
    // @DataJpaTest for the repository tests
    testImplementation(libs.spring.boot.starter.data.jpa.test)
```

`@DataJpaTest` comes from this starter in Spring Boot 4, in the package `org.springframework.boot.data.jpa.test.autoconfigure`.

**1.2.3** Don't add Lombok. user-config-service uses it, but sections 3 and 4 write its generated constructors out by hand.

### 1.3 Check the build

**1.3.1** Run:

```bash
./gradlew :app:dependencies --configuration runtimeClasspath | grep springdoc
```

Expect a line with `org.springdoc:springdoc-openapi-starter-webmvc-ui:3.2.0`.

## 2. Copy the classes that need only a new package

### 2.1 Copy them

**2.1.1** Run, with `$UC` set as above:

```bash
for f in FruitListOutOfDateException UserAlreadyExistsException UserNotFoundException \
         SettingsSnapshot UserSummary FruitRepository UserRepository UserSettingsRepository; do
  expand -t4 "$UC/main/java/com/example/demo/user/$f.java" \
    | sed 's/package com\.example\.demo\.user;/package org.example.user;/' \
    > "app/src/main/java/org/example/user/$f.java"
done
```

- `expand -t4` turns user-config-service's tabs into 4 spaces, the style of this project.
- `sed` changes only the `package` line.

**2.1.2** Check that nothing still points at the old package, and that no tabs are left:

```bash
grep -rn 'com.example\|lombok' app/src/main/java/org/example/user/    # expect no output
grep -rlP '\t' app/src/main/java/org/example/user/                    # expect no output
```

### 2.2 Compare with the expected result

**2.2.1** `UserRepository.java` must read:

```java
package org.example.user;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByUsername(String username);

    /** Projects straight into UserSummary, so each user's settings aren't loaded. */
    List<UserSummary> findAllByOrderByIdAsc();

    boolean existsByUsername(String username);

    boolean existsByEmail(String email);

}
```

Spring Data turns each method name into a query on `User`, which is mapped to the table `app_user`. No SQL or table name appears in the code, so the rename from `user` to `app_user` needs no change here. `findAllByOrderByIdAsc` returns the `UserSummary` record directly (a "projection"), so it never loads users' settings.

**2.2.2** `FruitRepository.java` must read:

```java
package org.example.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FruitRepository extends JpaRepository<Fruit, Long> {

    /** Active fruits in data.sql order (ids are assigned in that order). */
    List<Fruit> findAllByActiveTrueOrderByIdAsc();

}
```

`ActiveTrue` becomes `WHERE active = TRUE`, which works on Oracle's `BOOLEAN` column.

**2.2.3** `UserSettingsRepository.java` must read:

```java
package org.example.user;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

public interface UserSettingsRepository extends JpaRepository<UserSettings, Long> {

    Optional<UserSettings> findByUserId(Long userId);

}
```

**2.2.4** `SettingsSnapshot.java` must read:

```java
package org.example.user;

import java.util.List;

/**
 * A user's settings, fully loaded inside the service transaction. {@code fruits} is the
 * display order: retired fruits removed, newly added ones appended.
 */
public record SettingsSnapshot(long userId, String username, int resultSize, int fontSize, List<Fruit> fruits) {
}
```

**2.2.5** `UserSummary.java` must read:

```java
package org.example.user;

import java.time.Instant;

/** A user without their settings; filled directly by a repository query (no entity loading). */
public record UserSummary(Long id, String username, String email, Instant createdAt) {
}
```

**2.2.6** `FruitListOutOfDateException.java` must read:

```java
package org.example.user;

/** The submitted fruit order doesn't match the current set of active fruits (mapped to 409). */
public class FruitListOutOfDateException extends RuntimeException {

    public FruitListOutOfDateException() {
        super("fruit_ids must contain every active fruit exactly once; the fruit list has changed, reload and try again");
    }

}
```

**2.2.7** `UserAlreadyExistsException.java` must read:

```java
package org.example.user;

/** Username or email is already taken (mapped to 409). */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String field, String value) {
        super("A user with " + field + " '" + value + "' already exists");
    }

}
```

**2.2.8** `UserNotFoundException.java` must read:

```java
package org.example.user;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("No user with id " + userId);
    }

}
```

## 3. Copy `UserService` without Lombok

### 3.1 Copy the file

**3.1.1** Copy it the same way as section 2:

```bash
expand -t4 "$UC/main/java/com/example/demo/user/UserService.java" \
  | sed 's/package com\.example\.demo\.user;/package org.example.user;/' \
  > app/src/main/java/org/example/user/UserService.java
```

### 3.2 Remove Lombok

**3.2.1** Delete the Lombok import and the blank line above it:

```java
import lombok.RequiredArgsConstructor;
```

**3.2.2** Delete the annotation `@RequiredArgsConstructor` above the class.

**3.2.3** Add the constructor that Lombok generated, after the two fields. The top of the class then reads:

```java
@Service
public class UserService {

    private final UserRepository users;

    private final FruitRepository fruits;

    public UserService(UserRepository users, FruitRepository fruits) {
        this.users = users;
        this.fruits = fruits;
    }
```

With a single constructor, Spring injects both repositories through it; no `@Autowired` is needed.

**3.2.4** Leave the rest of the class unchanged. Every method works on Oracle as it is:

| Method | Does | Oracle note |
|---|---|---|
| `createUser(username, email)` | Checks for blanks and duplicates, creates the user with defaults and every active fruit | Inserts into `app_user`, `user_settings` and `user_settings_fruit` through JPA |
| `listUsers()` | All users, ordered by id, without settings | Uses the `UserSummary` projection |
| `getSettings(userId)` | Settings with retired fruits hidden and new fruits appended | Read-only transaction |
| `updateResultSize`, `updateFontSize` | Changes one size; values below 1 are refused | |
| `updateFruitOrder(userId, fruitIds)` | Saves a new order; the ids must be exactly the active fruits | Retired fruits keep their slots, so they come back in place |

**3.2.5** Know that the service returns a `SettingsSnapshot` built inside its transaction. `spring.jpa.open-in-view=false` (MODEL.md) closes the session when the transaction ends, so the controller must never touch lazy associations.

### 3.3 Check it

**3.3.1** Check that Lombok is gone:

```bash
grep -n 'lombok\|RequiredArgsConstructor' app/src/main/java/org/example/user/UserService.java   # expect no output
```

## 4. Copy the controller as `PreferencesController`

### 4.1 Copy and rename the file

**4.1.1** Copy it under its new name:

```bash
expand -t4 "$UC/main/java/com/example/demo/user/UserSettingsController.java" \
  | sed -e 's/package com\.example\.demo\.user;/package org.example.user;/' \
        -e 's/\bUserSettingsController\b/PreferencesController/g' \
  > app/src/main/java/org/example/user/PreferencesController.java
```

The file name and the class name must match: `PreferencesController.java` holds `public class PreferencesController`.

### 4.2 Remove Lombok

**4.2.1** Delete `import lombok.RequiredArgsConstructor;` and the `@RequiredArgsConstructor` annotation.

**4.2.2** Add the constructor after the field `private final UserService userService;`:

```java
    public PreferencesController(UserService userService) {
        this.userService = userService;
    }
```

### 4.3 Update the description

**4.3.1** Change the class comment and the Swagger tag. The top of the class reads:

```java
/**
 * Users and their preferences (settings), keyed by user id. Every settings call returns the full settings.
 * Imported from user-config-service's UserSettingsController; the endpoints are unchanged.
 */
@RestController
@RequestMapping("/users")
@Tag(name = "User preferences")
public class PreferencesController {

    private final UserService userService;

    public PreferencesController(UserService userService) {
        this.userService = userService;
    }
```

### 4.4 Leave the rest unchanged

**4.4.1** Keep the request and response records, the endpoints and the three `@ExceptionHandler` methods exactly as they are.

**4.4.2** Keep `@RequestMapping("/users")`, which keeps the URLs the same as in user-config-service. To serve them under another path, such as `/preferences`, change only that line and the `URI.create("/users/" + ...)` in `createUser`, and update the tests' URLs to match.

**4.4.3** Keep `import com.fasterxml.jackson.annotation.JsonProperty;` as it is. Spring Boot 4 uses Jackson 3 (`tools.jackson`), but Jackson 3 still reads the annotations from the old `com.fasterxml.jackson.annotation` package.

### 4.5 Know the API

| Method | Path | Body | Success | Errors |
|---|---|---|---|---|
| `GET` | `/users` | none | 200, list of users without settings | |
| `POST` | `/users` | `{"username": "frank", "email": "frank@example.com"}` | 201, full settings, `Location: /users/{id}/settings` | 400 blank field; 409 username or email taken |
| `GET` | `/users/{userId}/settings` | none | 200, full settings | 404 unknown user |
| `POST` | `/users/{userId}/result_size` | `{"result_size": 35}` | 200, full settings | 400 missing, not a number, or below 1; 404 |
| `POST` | `/users/{userId}/font_size` | `{"font_size": 16}` | 200, full settings | 400; 404 |
| `POST` | `/users/{userId}/fruits` | `{"fruit_ids": [3, 1, 6, 2, 5, 4]}` | 200, full settings | 400 empty, null, duplicate or unknown ids; 404; 409 not exactly the active fruits |

**4.5.1** Errors come back as RFC 9457 problem details:

```json
{"detail":"No user with id 999999","instance":"/users/999999/settings","status":404,"title":"Not Found"}
```

### 4.6 Compile

**4.6.1** Run:

```bash
./gradlew :app:compileJava
```

Expect `BUILD SUCCESSFUL`. Your IDE may still show "cannot be resolved" errors until it re-imports the Gradle project.

## 5. Swagger UI

### 5.1 Know the defaults

**5.1.1** springdoc needs no configuration. With the dependency from step 1.2.1:

- Swagger UI is at `http://localhost:8080/swagger-ui.html` (it redirects to `/swagger-ui/index.html`);
- the OpenAPI document is at `http://localhost:8080/v3/api-docs`.

The `@Operation`, `@ApiResponse`, `@Parameter(example = ...)` and `@Schema(example = ...)` annotations in the controller supply the descriptions and the example request bodies.

**5.1.2** Know that Swagger UI is on for everyone who can reach the service. Before deploying, turn it off with these lines in `application.properties`:

```properties
springdoc.swagger-ui.enabled=false
springdoc.api-docs.enabled=false
```

## 6. Port the tests from SQLite to Oracle

The four test classes ran on a SQLite file, `build/test.db`. Here they run on a throwaway Oracle container from `TestcontainersConfiguration`.

### 6.1 Copy the four test classes

**6.1.1** Copy them, renaming `UserSettingsControllerTests` to `PreferencesControllerTests`:

```bash
T=$UC/test/java/com/example/demo/user
D=app/src/test/java/org/example/user
for f in CreateUserControllerTests FruitOrderControllerTests UserSettingsRepositoryTests; do
  expand -t4 "$T/$f.java" | sed 's/package com\.example\.demo\.user;/package org.example.user;/' > "$D/$f.java"
done
expand -t4 "$T/UserSettingsControllerTests.java" \
  | sed -e 's/package com\.example\.demo\.user;/package org.example.user;/' \
        -e 's/\bUserSettingsControllerTests\b/PreferencesControllerTests/g' \
  > "$D/PreferencesControllerTests.java"
```

Then make the changes in steps 6.2 to 6.5 by hand.

### 6.2 Know the SQLite-to-Oracle differences in the tests

| SQLite (original test) | Oracle (this project) | Why |
|---|---|---|
| `@SpringBootTest(properties = "spring.datasource.url=jdbc:sqlite:build/test.db?foreign_keys=true")` | `@SpringBootTest` + `@Import(TestcontainersConfiguration.class)` | `@ServiceConnection` points the data source at the Oracle container |
| `@DataJpaTest(properties = "...sqlite...")` and `@Import(UserService.class)` | `@DataJpaTest` + `@Import({ TestcontainersConfiguration.class, UserService.class })` | The same, for the JPA slice |
| SQL `FROM user`, `JOIN user`, `INSERT INTO user` | `app_user` | `USER` is reserved in Oracle (MODEL.md 4.1) |
| `UPDATE fruit SET active = ?` with `active ? 1 : 0` | the `boolean` itself | `fruit.active` is a real `BOOLEAN` on Oracle |
| `"NOT NULL constraint failed: user.username"` | `"ORA-01400"` and `"\"APP_USER\".\"USERNAME\""` | Oracle's message: `ORA-01400: cannot insert NULL into ("PREFERENCES"."APP_USER"."USERNAME")` |
| `"UNIQUE constraint failed"` | `"ORA-00001"` and `"UQ_USER_SETTINGS_USER_ID"` | Oracle names the constraint: `ORA-00001: unique constraint (PREFERENCES.UQ_USER_SETTINGS_USER_ID) violated` |
| `queryForMap(...)` with `.containsEntry("results_size", 20)` | `queryForObject(..., Integer.class)` | Oracle returns `NUMBER` as `BigDecimal`, and `BigDecimal` 20 doesn't equal `Integer` 20 |

### 6.3 Change the three controller tests

These are `CreateUserControllerTests`, `FruitOrderControllerTests` and `PreferencesControllerTests`.

**6.3.1** Add two imports to each. Put `org.example.TestcontainersConfiguration` before `org.junit...`, and `org.springframework.context.annotation.Import` after `...AutoConfigureMockMvc`:

```java
import org.example.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
...
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
```

**6.3.2** Replace the SQLite `@SpringBootTest(properties = ...)` line in each with:

```java
@SpringBootTest
@Import(TestcontainersConfiguration.class)
```

`@AutoConfigureMockMvc` and `@Transactional` stay. Each test's rows are rolled back.

**6.3.3** In `PreferencesControllerTests`, change the table in `storedSetting` from `user` to `app_user`:

```java
        return jdbc.queryForObject("SELECT s." + column + """
                 FROM user_settings s
                JOIN app_user u ON u.id = s.user_id WHERE u.username = 'frank'
                """, Integer.class);
```

This method already asks for `Integer.class`, so Oracle's `BigDecimal` is converted. It needs no other change.

**6.3.4** In `FruitOrderControllerTests`, change the table in `storedFruitIds` to `FROM app_user u`:

```java
        return jdbc.queryForList("""
                SELECT usf.fruit_id
                FROM app_user u
                JOIN user_settings s ON s.user_id = u.id
                JOIN user_settings_fruit usf ON usf.user_settings_id = s.id
                WHERE u.username = 'gina'
                ORDER BY usf.position
                """, Long.class);
```

**6.3.5** In `FruitOrderControllerTests`, pass the boolean itself in `setActive`:

```java
    private void setActive(long fruitId, boolean active) {
        em.flush();
        jdbc.update("UPDATE fruit SET active = ? WHERE id = ?", active, fruitId);
        em.clear();
    }
```

**6.3.6** Optionally, fix the class comment of `FruitOrderControllerTests`. It says `{username}`, but the endpoints take a user id:

```java
/** GET /users/{userId}/settings and POST /users/{userId}/fruits, including fruits being added and retired. */
```

**6.3.7** Leave `addFruit` as it is. `INSERT INTO fruit (id, name) VALUES (?, ?)` works on Oracle, and `active` takes its default, `TRUE`.

### 6.4 Change `UserSettingsRepositoryTests`

**6.4.1** Add the import:

```java
import org.example.TestcontainersConfiguration;
```

**6.4.2** Replace the annotations above the class with:

```java
@DataJpaTest
@Import({ TestcontainersConfiguration.class, UserService.class })
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class UserSettingsRepositoryTests {
```

`Replace.NONE` stops `@DataJpaTest` from swapping in an embedded database, so it uses the Oracle container. Delete the separate `@Import(UserService.class)` line: a class can carry only one `@Import`.

**6.4.3** Add this helper method before `fruitsInOrder`:

```java
    /**
     * One stored size, read as an Integer. Oracle returns NUMBER columns as BigDecimal from a plain queryForMap, and
     * BigDecimal 20 is not equal to Integer 20; asking for Integer.class converts it. The column name comes only from
     * this class, never from input.
     */
    private Integer storedSize(String column, User user) {
        return jdbc.queryForObject("SELECT " + column + " FROM user_settings WHERE user_id = ?", Integer.class,
                user.getId());
    }
```

**6.4.4** In `newUserGetsDefaultSettings`, replace the `queryForMap(...)` block on `user_settings` with:

```java
        assertThat(storedSize("results_size", erin)).isEqualTo(20);
        assertThat(storedSize("font_size", erin)).isEqualTo(12);
```

**6.4.5** In `insertsUserWithSettings`:

- change `FROM user WHERE` to `FROM app_user WHERE` in the first `queryForMap`. That one compares strings, so it can stay a map.
- replace the `queryForMap(...)` block on `user_settings` with:

```java
        assertThat(storedSize("results_size", dave)).isEqualTo(50);
        assertThat(storedSize("font_size", dave)).isEqualTo(16);
```

**6.4.6** In `rejectsUserWithoutUsername`, change the table and the expected message:

```java
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, email, created_at) VALUES (NULL, 'x@example.com', CURRENT_TIMESTAMP)"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ORA-01400").hasMessageContaining("\"APP_USER\".\"USERNAME\"");
```

**6.4.7** In `rejectsUserWithoutEmail`, do the same:

```java
        assertThatThrownBy(() -> jdbc.update(
                "INSERT INTO app_user (username, email, created_at) VALUES ('noemail', NULL, CURRENT_TIMESTAMP)"))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ORA-01400").hasMessageContaining("\"APP_USER\".\"EMAIL\"");
```

**6.4.8** In `allowsOnlyOneSettingsRowPerUser`, expect Oracle's unique-constraint error and the constraint's name from `schema.sql`:

```java
        assertThatThrownBy(() -> settings.saveAndFlush(new UserSettings(bob)))
            .isInstanceOf(DataAccessException.class)
            .hasMessageContaining("ORA-00001").hasMessageContaining("UQ_USER_SETTINGS_USER_ID");
```

`new UserSettings(bob)` uses the package-private constructor. This works because the test is in the same package, `org.example.user`.

### 6.5 Check that nothing SQLite-specific is left

**6.5.1** Run:

```bash
grep -rn 'sqlite\|com\.example\|constraint failed\|FROM user \|JOIN user \|INTO user \|? 1 : 0' app/src/test/java/org/example/user/
```

Expect no output.

## 7. Run the tests

### 7.1 Run the whole suite

**7.1.1** Run, with Docker running:

```bash
make test
```

Expect `BUILD SUCCESSFUL` after about 2 minutes, with **38 tests, 0 failures**:

| Class | Tests | Covers |
|---|---|---|
| `AppTest` | 2 | Context starts; Oracle connection |
| `GreetingControllerTest` | 1 | `GET /api/greeting` |
| `UserSchemaTest` | 6 | Schema, seeding, scripts re-runnable (MODEL.md) |
| `CreateUserControllerTests` | 4 | `POST /users` defaults, 400, 409; `GET /users` order |
| `PreferencesControllerTests` | 10 | `result_size` and `font_size` updates; 400 and 404 |
| `FruitOrderControllerTests` | 8 | Reordering; 400, 404, 409; retired, added and reactivated fruits |
| `UserSettingsRepositoryTests` | 8 | Repository mapping, defaults, NOT NULL and UNIQUE constraints, ordering |

**7.1.2** Know why it takes longer than before. `@DataJpaTest` starts a different Spring context from `@SpringBootTest`, so it gets its own Oracle container. Two containers start in all.

### 7.2 Run one class

**7.2.1** Run:

```bash
make test TEST=org.example.user.PreferencesControllerTests
```

### 7.3 Read failures

**7.3.1** Open `app/build/reports/tests/test/index.html`, or print the failure messages:

```bash
grep -h -A3 '<failure' app/build/test-results/test/*.xml | head -40
```

## 8. Try the API on the local database

### 8.1 Start the service

**8.1.1** Run:

```bash
make run          # starts preferences-oracle if needed; Ctrl+C stops the service
```

### 8.2 Call every endpoint

**8.2.1** In a second terminal, run:

```bash
B=localhost:8080; J='Content-Type: application/json'

curl -s -X POST $B/users -H "$J" -d '{"username":"frank","email":"frank@example.com"}'   # 201
curl -s $B/users                                                                      # 200
curl -s $B/users/1/settings                                                           # 200
curl -s -X POST $B/users/1/result_size -H "$J" -d '{"result_size":35}'                # 200
curl -s -X POST $B/users/1/font_size   -H "$J" -d '{"font_size":16}'                  # 200
curl -s -X POST $B/users/1/fruits      -H "$J" -d '{"fruit_ids":[3,1,6,2,5,4]}'       # 200
```

Use the `user_id` that the first call returns in place of `1`. Add `-i` to any `curl` to see the status line and headers, such as `Location`.

**8.2.2** Expect the last call to return:

```json
{"user_id":1,"username":"frank","result_size":35,"font_size":16,"fruits":[{"id":3,"name":"banana"},{"id":1,"name":"apple"},{"id":6,"name":"orange"},{"id":2,"name":"pear"},{"id":5,"name":"melon"},{"id":4,"name":"strawberry"}]}
```

### 8.3 Try the errors

**8.3.1** Run:

```bash
curl -s -X POST $B/users -H "$J" -d '{"username":"frank","email":"x@example.com"}'    # 409 username taken
curl -s $B/users/999999/settings                                                   # 404
curl -s -X POST $B/users/1/font_size -H "$J" -d '{"font_size":0}'                  # 400
curl -s -X POST $B/users/1/fruits -H "$J" -d '{"fruit_ids":[1,2,3]}'               # 409 not every active fruit
```

### 8.4 Check the stored rows

**8.4.1** Run:

```bash
make db-users      # 1 frank frank@example.com 35 16 banana, apple, orange, pear, melon, strawberry
```

### 8.5 Watch a fruit being retired and brought back

**8.5.1** Run:

```bash
make db-retire FRUIT=6
curl -s $B/users/1/settings          # orange is gone from the list
make db-activate FRUIT=6
curl -s $B/users/1/settings          # orange is back where frank had it
```

### 8.6 Open Swagger UI

**8.6.1** Open `http://localhost:8080/swagger-ui.html`. Under **User preferences**, each endpoint has **Try it out** with an example body.

### 8.7 Clean up

**8.7.1** Remove the test user. The settings and fruit rows go with it, through `ON DELETE CASCADE`:

```bash
make db-query SQL="DELETE FROM app_user WHERE username = 'frank';"
```

## 9. Fix common problems

| Symptom | Cause | Fix |
|---|---|---|
| `package lombok does not exist` | A `@RequiredArgsConstructor` or Lombok import is left | Steps 3.2 and 4.2 |
| `class PreferencesController is public, should be declared in a file named ...` | The file and class names differ | Step 4.1.1 |
| `No qualifying bean of type 'UserService'` in `UserSettingsRepositoryTests` | `@DataJpaTest` loads only JPA beans | `@Import({ TestcontainersConfiguration.class, UserService.class })` (step 6.4.2) |
| `Failed to replace DataSource with an embedded database` | `@AutoConfigureTestDatabase(replace = NONE)` is missing | Step 6.4.2 |
| `Expecting map ... ["results_size"=20 (expected: 20)]` | Oracle returned `BigDecimal` | Read with `Integer.class` (steps 6.4.3 to 6.4.5) |
| `ORA-00942: table or view "PREFERENCES"."USER" does not exist` (or `ORA-00903`) | A test SQL still says `user` | Step 6.3 or 6.4; check with step 6.5.1 |
| Test expects `NOT NULL constraint failed` | The SQLite message is left | Steps 6.4.6 to 6.4.8 |
| `Could not find a valid Docker environment` | Docker isn't running | Start Docker |
| `/swagger-ui.html` gives 404 | The springdoc dependency is missing, or the wrong version for Spring Boot 4 | Step 1; use the 3.x line |
| `POST` returns 415 Unsupported Media Type | No JSON content type | Add `-H 'Content-Type: application/json'` |
| `POST` with a number as text (`{"font_size":"big"}`) returns 400 with no `detail` | Jackson can't read the body; Spring answers before the controller | Expected behaviour |

## 10. Know the limits

### 10.1 Two requests creating the same user at once

**10.1.1** Know that `UserService.createUser` checks `existsByUsername` and `existsByEmail` before it inserts. If two requests create the same username at the same moment, both checks can pass. The database's unique constraint (`UQ_APP_USER_USERNAME`) then refuses the second insert. No duplicate is stored, but the second request gets a 500 instead of a 409. This was the same on SQLite. To answer 409, add an `@ExceptionHandler(DataIntegrityViolationException.class)` that returns `HttpStatus.CONFLICT`.

### 10.2 No authentication

**10.2.1** Know that anyone who can reach the service can read and change any user's settings, and that user ids are easy to guess.

### 10.3 Swagger UI is on

**10.3.1** Turn it off before deploying (step 5.1.2).
