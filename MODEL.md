# Adding the user-settings model and schema on Oracle

These steps bring the user-settings schema and JPA model from `user-config-service` (SQLite) into preferences-service and run them on Oracle. The steps:

- give the service its own Oracle database, `PREFERENCES`;
- create the tables at startup and seed the fruit list;
- add a test suite that runs on a throwaway Oracle;
- add commands for working with the database.

This repository went through these exact steps.

**Starting point:** the service with JPA, JDBC and an Oracle connection, as [DATABASE.md](DATABASE.md) describes. That means the local container `preferences-oracle` on `127.0.0.1:1525`, `scripts/oracle-db.sh` with `start`, `stop`, `status` and `remove`, and the Testcontainers test setup.

**Where things come from:** `../user-config-service/user-config-service/src/main/`:

| Source (user-config-service) | Destination (preferences-service) |
|---|---|
| `resources/schema.sql` (SQLite) | `app/src/main/resources/schema.sql` (Oracle) |
| `resources/data.sql` (SQLite) | `app/src/main/resources/data.sql` (Oracle) |
| `java/com/example/demo/user/User.java` | `app/src/main/java/org/example/user/User.java` |
| `java/com/example/demo/user/UserSettings.java` | `app/src/main/java/org/example/user/UserSettings.java` |
| `java/com/example/demo/user/Fruit.java` | `app/src/main/java/org/example/user/Fruit.java` |

The other classes in that package aren't part of the model, so they are not brought over: the repositories, `UserService`, the controller, the exceptions and the records.

**The end result:**

```
Oracle container preferences-oracle (127.0.0.1:1525)
└── pluggable database PREFERENCES   (the service's own database; URL service name PREFERENCES)
    └── schema PREFERENCES           (owned by the login "preferences")
        ├── app_user                 (a user)
        ├── user_settings            (one row per user)
        ├── user_settings_fruit      (each user's ordered fruit list)
        └── fruit                    (the shared fruit list, seeded with 6 fruits)
```

Run all commands from the repository root, `preferences-service/`.

## 1. Give the service its own database, `PREFERENCES`

An Oracle container holds several pluggable databases (PDBs). The image always creates one called `FREEPDB1`. Here the service gets its own PDB, `PREFERENCES`, which holds nothing but the service's tables.

### 1.1 Make the script create the `PREFERENCES` database

**1.1.1** Open `scripts/oracle-db.sh`. Below the line `PORT=${PREF_DB_PORT:-1525}`, add:

```bash
# The pluggable database (and service name) the application uses. The image creates it on the first start.
DATABASE=${PREF_DB_SERVICE:-PREFERENCES}
```

**1.1.2** In the `start)` branch, add `-e "ORACLE_DATABASE=$DATABASE"` to the `docker run` command, after `--env-file`:

```bash
        docker run -d --name "$CONTAINER" \
            -p "127.0.0.1:$PORT:1521" \
            --env-file "$ENV_FILE" \
            -e "ORACLE_DATABASE=$DATABASE" \
            -v "$VOLUME:/opt/oracle/oradata" \
            "$IMAGE" >/dev/null
```

On a container's first start, the image creates the pluggable database `ORACLE_DATABASE` and the login `APP_USER` (`preferences`) inside it. The value isn't secret, so it can go on the command line, unlike the passwords in `--env-file`.

**1.1.3** In `wait_ready`, change the "ready" message to name the database:

```bash
            echo "oracle-db: ready on 127.0.0.1:$PORT, database (service) $DATABASE, login preferences"
```

### 1.2 Fix the readiness check

The first version of `wait_ready` had two bugs:

- **False "not ready":** with `set -o pipefail`, `docker logs ... | grep -q` fails whenever `grep -q` stops reading at the first match, because `docker logs` then gets a broken pipe. After a restart the log is long, so this happens almost every time, and `make db-start` waited 269 seconds for a database that was already up.
- **Stale match:** the log still holds the previous start's `DATABASE IS READY TO USE!`, which could make the script report ready too early.

**1.2.1** Replace `wait_ready` with this version. It reads only the log lines written since this start, and lets `grep` read all of its input:

```bash
# Waits for this start's "ready" line. $1 is the time the container was started: older log lines (a previous start's
# "ready") are skipped. grep reads all of its input (no -q): with -q it would exit at the first match, docker logs
# would fail with a broken pipe, and pipefail would turn the match into a failure.
wait_ready() {
    local since=$1
    echo "oracle-db: waiting for the database (the first start takes about a minute)..."
    for _ in $(seq 1 180); do
        if docker logs --since "$since" "$CONTAINER" 2>&1 | grep 'DATABASE IS READY TO USE!' >/dev/null; then
            echo "oracle-db: ready on 127.0.0.1:$PORT, database (service) $DATABASE, login preferences"
            return 0
        fi
        if ! running; then
            echo "oracle-db: the container stopped; see: docker logs $CONTAINER" >&2
            return 1
        fi
        sleep 2
    done
    echo "oracle-db: not ready after 6 minutes; see: docker logs $CONTAINER" >&2
    return 1
}
```

**1.2.2** In the `start)` branch, record the time just before `docker start` and before `docker run`, and pass it on:

```bash
    elif exists; then
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        docker start "$CONTAINER" >/dev/null
        wait_ready "$since"
    else
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        # ... docker run as in step 1.1.2 ...
        wait_ready "$since"
    fi
```

Section 9 adds the data commands to the script, and step 9.1.1 gives the whole finished file.

### 1.3 Recreate the container

`ORACLE_DATABASE` takes effect only when a container is created, so the existing one has to be replaced.

**1.3.1** Look at what the old container holds first. Removing it deletes its data:

```bash
docker exec -i preferences-oracle bash -c 'sqlplus -s / as sysdba' <<'EOF'
SELECT name FROM v$pdbs;
EOF
```

**1.3.2** Delete the container and its data volume. The password file `~/.config/preferences-service/oracle.env` is kept, so the new container gets the same passwords.

```bash
scripts/oracle-db.sh remove
```

Expect: `oracle-db: preferences-oracle and its volume preferences-oracle-data removed (the password file is kept)`.

**1.3.3** Create the new container. This takes about a minute, or several on the first image pull.

```bash
scripts/oracle-db.sh start
```

Expect: `oracle-db: ready on 127.0.0.1:1525, database (service) PREFERENCES, login preferences`.

**1.3.4** Check that the pluggable databases are there:

```bash
docker exec -i preferences-oracle bash -c 'sqlplus -s / as sysdba' <<'EOF'
SELECT name, open_mode FROM v$pdbs ORDER BY name;
EOF
```

Expect `FREEPDB1 READ WRITE`, `PDB$SEED READ ONLY` and `PREFERENCES READ WRITE`. The image always creates `FREEPDB1`, with an unused copy of the `preferences` login and no tables. `PDB$SEED` is Oracle's own template and can't be removed.

## 2. Point the service at `PREFERENCES` (`app/src/main/resources/application.properties`)

### 2.1 Change the service name

**2.1.1** Change the default service at the end of `spring.datasource.url` from `FREEPDB1` to `PREFERENCES`, and update the comment above it:

```properties
# The URL names the Oracle service PREFERENCES: the pluggable database of that name, which holds only this service's
# tables (schema PREFERENCES). The password is never written here: it must come from the PREF_DB_PASSWORD environment
# variable (`make run` sets it from ~/.config/preferences-service/oracle.env).
spring.datasource.url=jdbc:oracle:thin:@//${PREF_DB_HOST:localhost}:${PREF_DB_PORT:1525}/${PREF_DB_SERVICE:PREFERENCES}
```

`PREF_DB_SERVICE` overrides it, and the script reads the same variable (step 1.1.1), so both always agree.

## 3. Give the test database the same names (`app/src/test/java/org/example/TestcontainersConfiguration.java`)

### 3.1 Name the test container's database and login

**3.1.1** Change the bean method to:

```java
    @Bean
    @ServiceConnection
    OracleContainer oracleContainer() {
        // The same database and login names as the local container (scripts/oracle-db.sh).
        return new OracleContainer(ORACLE_IMAGE).withDatabaseName("preferences").withUsername("preferences");
    }
```

`withDatabaseName` sets the same `ORACLE_DATABASE` variable as step 1.1.2, and `@ServiceConnection` builds the matching JDBC URL. Tests then run against a database laid out like the local one.

## 4. Convert the schema from SQLite to Oracle (`app/src/main/resources/schema.sql`)

### 4.1 Understand what has to change

| SQLite (user-config-service) | Oracle (preferences-service) | Why |
|---|---|---|
| table `user` | table `app_user` | `USER` is a reserved word in Oracle SQL and can't name a table. `users` is avoided too: an `INSERT` into `user` is a typo away from `users` |
| `id INTEGER PRIMARY KEY` (implicit rowid alias, auto-numbered) | `id NUMBER(19) GENERATED BY DEFAULT AS IDENTITY` | Oracle needs an explicit identity column. `NUMBER(19)` holds a Java `Long` |
| `fruit.id INTEGER PRIMARY KEY` | `fruit.id NUMBER(19)` with no identity | Fruit ids are fixed by `data.sql`, never generated |
| `active INTEGER ... CHECK (active IN (0, 1))` | `active BOOLEAN DEFAULT TRUE NOT NULL` | Oracle 23ai and later has a real `BOOLEAN` type |
| `INTEGER` sizes | `NUMBER(10)` | Holds a Java `int` |
| `VARCHAR(255)` | `VARCHAR2(255 CHAR)` | `CHAR` counts characters, not bytes |
| `created_at TIMESTAMP` | `TIMESTAMP(6) WITH TIME ZONE` | Hibernate maps `java.time.Instant` to a time-zoned timestamp on Oracle |
| unnamed constraints | `CONSTRAINT pk_... / uq_... / fk_... / ck_...` | Errors and the data dictionary then name the constraint that was hit (for example `UQ_APP_USER_USERNAME`) |
| (none) | `CREATE INDEX ix_usf_fruit_id` | Oracle doesn't index foreign key columns automatically |
| `CREATE TABLE IF NOT EXISTS` | the same | Supported since Oracle 23ai; lets the script run at every startup |

### 4.2 Write the file

**4.2.1** Create `app/src/main/resources/schema.sql`:

```sql
-- User settings schema for Oracle AI Database 26ai (Oracle 23ai or later: BOOLEAN, IF NOT EXISTS).
-- Converted from user-config-service's SQLite schema.sql. Runs at every start-up (spring.sql.init.mode=always), before
-- Hibernate validates the entities in org.example.user against it (spring.jpa.hibernate.ddl-auto=validate).
-- IF NOT EXISTS never alters an existing table: to change one, migrate it by hand or drop it.
--
-- Differences from the SQLite original:
--   * the table "user" is app_user: USER is a reserved word in Oracle SQL;
--   * generated ids are identity columns (SQLite's INTEGER PRIMARY KEY is an implicit rowid alias);
--   * fruit.active is BOOLEAN (SQLite stored 0/1 with a CHECK);
--   * constraints and indexes are named, so errors and the data dictionary say which one was hit.
-- Statements end with ';' on their own line ending: Spring's script runner splits on ';'.

CREATE TABLE IF NOT EXISTS app_user (
    id          NUMBER(19) GENERATED BY DEFAULT AS IDENTITY CONSTRAINT pk_app_user PRIMARY KEY,
    username    VARCHAR2(255 CHAR) NOT NULL CONSTRAINT uq_app_user_username UNIQUE,
    email       VARCHAR2(255 CHAR) NOT NULL CONSTRAINT uq_app_user_email UNIQUE,
    created_at  TIMESTAMP(6) WITH TIME ZONE NOT NULL
);

-- Fruits are never deleted (user_settings_fruit references them, and a deleted row would leave a gap in users' ordered
-- lists). Retire one with active = FALSE; never reuse an id. Ids are fixed by data.sql, not generated.
CREATE TABLE IF NOT EXISTS fruit (
    id      NUMBER(19) CONSTRAINT pk_fruit PRIMARY KEY,
    name    VARCHAR2(50 CHAR) NOT NULL CONSTRAINT uq_fruit_name UNIQUE,
    active  BOOLEAN DEFAULT TRUE NOT NULL
);

-- One settings row per user, created with the user. Every column is NOT NULL.
CREATE TABLE IF NOT EXISTS user_settings (
    id            NUMBER(19) GENERATED BY DEFAULT AS IDENTITY CONSTRAINT pk_user_settings PRIMARY KEY,
    user_id       NUMBER(19) NOT NULL CONSTRAINT uq_user_settings_user_id UNIQUE
                  CONSTRAINT fk_user_settings_app_user REFERENCES app_user (id) ON DELETE CASCADE,
    results_size  NUMBER(10) DEFAULT 20 NOT NULL,
    font_size     NUMBER(10) DEFAULT 12 NOT NULL
);

-- The user's ordered fruit list (a join table). position is the 0-based index in the list; Hibernate maintains it via
-- @OrderColumn.
CREATE TABLE IF NOT EXISTS user_settings_fruit (
    user_settings_id  NUMBER(19) NOT NULL
                      CONSTRAINT fk_usf_user_settings REFERENCES user_settings (id) ON DELETE CASCADE,
    position          NUMBER(10) NOT NULL CONSTRAINT ck_usf_position CHECK (position >= 0),
    fruit_id          NUMBER(19) NOT NULL CONSTRAINT fk_usf_fruit REFERENCES fruit (id),
    CONSTRAINT pk_user_settings_fruit PRIMARY KEY (user_settings_id, position)
);

-- Oracle does not index foreign key columns automatically.
CREATE INDEX IF NOT EXISTS ix_usf_fruit_id ON user_settings_fruit (fruit_id);
```

**4.2.2** Keep the tables in this order. A table must exist before another table's foreign key can refer to it.

**4.2.3** Don't add SQL*Plus commands such as `WHENEVER SQLERROR` or `SET DEFINE OFF`. Spring's script runner sends each statement to the database, and the database doesn't understand them.

## 5. Convert the seed data (`app/src/main/resources/data.sql`)

### 5.1 Replace `INSERT OR IGNORE`

SQLite's `INSERT OR IGNORE` doesn't exist in Oracle. A `MERGE` that only inserts does the same job: it adds the fruits that are missing and leaves the existing ones alone, so a retired fruit stays retired.

**5.1.1** Create `app/src/main/resources/data.sql`:

```sql
-- The shared fruit list, seeded at every start-up (spring.sql.init.mode=always). Converted from user-config-service's
-- SQLite "INSERT OR IGNORE": the MERGE inserts only the ids that are missing, so it is safe to run again, and it never
-- changes an existing row (a retired fruit stays retired).
-- To add a fruit, add a row with a NEW id. Never reuse or delete an id: saved orders refer to fruits by id.
MERGE INTO fruit f
USING (
    SELECT 1 AS id, 'apple' AS name FROM dual UNION ALL
    SELECT 2, 'pear' FROM dual UNION ALL
    SELECT 3, 'banana' FROM dual UNION ALL
    SELECT 4, 'strawberry' FROM dual UNION ALL
    SELECT 5, 'melon' FROM dual UNION ALL
    SELECT 6, 'orange' FROM dual
) s
ON (f.id = s.id)
WHEN NOT MATCHED THEN INSERT (id, name) VALUES (s.id, s.name);
```

`active` isn't listed in the insert, so it takes the column default, `TRUE`.

## 6. Run the scripts at startup (`app/src/main/resources/application.properties`)

### 6.1 Turn on script initialization

**6.1.1** Replace the JPA block and the `spring.sql.init.mode=never` line with:

```properties
# --- Schema and seed data: schema.sql (CREATE ... IF NOT EXISTS) and data.sql (MERGE of the fruit list) run at every
# start-up, before Hibernate starts. Both are safe to run again.
spring.sql.init.mode=always

# --- JPA / Hibernate. validate: Hibernate checks the entities (org.example.user) against the tables schema.sql
# created and never changes the schema; if validation fails, fix the entity or schema.sql, not this setting.
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
# The Oracle driver fetches 10 rows per round trip by default; Hibernate warns about it.
spring.jpa.properties.hibernate.jdbc.fetch_size=100
```

Spring Boot runs `schema.sql`, then `data.sql`, then starts Hibernate, which checks every entity against the tables. Keep `validate`: the schema belongs to `schema.sql`, and Hibernate never changes it.

**6.1.2** Don't set `spring.jpa.defer-datasource-initialization=true`. It would start Hibernate first, and validation would fail on a database that has no tables yet.

### 6.2 Check the whole file

**6.2.1** The finished `application.properties` reads:

```properties
spring.application.name=preferences-service
server.port=8080

# Actuator: expose only health and info over HTTP. Health includes the database ("db" component).
management.endpoints.web.exposure.include=health,info

# --- Oracle database: container preferences-oracle (scripts/oracle-db.sh), published on 127.0.0.1:1525.
# The URL names the Oracle service PREFERENCES: the pluggable database of that name, which holds only this service's
# tables (schema PREFERENCES). The password is never written here: it must come from the PREF_DB_PASSWORD environment
# variable (`make run` sets it from ~/.config/preferences-service/oracle.env).
spring.datasource.url=jdbc:oracle:thin:@//${PREF_DB_HOST:localhost}:${PREF_DB_PORT:1525}/${PREF_DB_SERVICE:PREFERENCES}
spring.datasource.username=${PREF_DB_USER:preferences}
spring.datasource.password=${PREF_DB_PASSWORD}

# --- Connection pool (HikariCP)
spring.datasource.hikari.pool-name=preferences-pool
spring.datasource.hikari.maximum-pool-size=5
spring.datasource.hikari.connection-timeout=10000

# --- Schema and seed data: schema.sql (CREATE ... IF NOT EXISTS) and data.sql (MERGE of the fruit list) run at every
# start-up, before Hibernate starts. Both are safe to run again.
spring.sql.init.mode=always

# --- JPA / Hibernate. validate: Hibernate checks the entities (org.example.user) against the tables schema.sql
# created and never changes the schema; if validation fails, fix the entity or schema.sql, not this setting.
spring.jpa.hibernate.ddl-auto=validate
spring.jpa.open-in-view=false
# The Oracle driver fetches 10 rows per round trip by default; Hibernate warns about it.
spring.jpa.properties.hibernate.jdbc.fetch_size=100
# Uncomment to see the SQL Hibernate sends:
#logging.level.org.hibernate.SQL=debug
```

## 7. Add the model (`app/src/main/java/org/example/user/`)

### 7.1 Create the package folder

**7.1.1** Run:

```bash
mkdir -p app/src/main/java/org/example/user app/src/test/java/org/example/user
```

The package is `org.example.user`, below `App`'s package `org.example`, so Spring Boot finds the entities without extra configuration.

### 7.2 Know what changes from the originals

**7.2.1** Change the package from `com.example.demo.user` to `org.example.user`.

**7.2.2** Remove Lombok (`@Getter`, `@Setter`, `@NoArgsConstructor`, `@Setter(AccessLevel.NONE)`) and write the constructors, getters and setters it generated. preferences-service has no Lombok, and three small classes don't justify a new dependency.

**7.2.3** Change `User`'s table to `@Table(name = "app_user")` (step 4.1).

**7.2.4** Give `Fruit.name` `length = 50`, to match `VARCHAR2(50 CHAR)`.

**7.2.5** Keep everything else unchanged: the JPA and Hibernate annotations, the defaults (20 and 12), and the ordered fruit list.

### 7.3 Create `Fruit.java`

**7.3.1** Create `app/src/main/java/org/example/user/Fruit.java`:

```java
package org.example.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Reference data seeded by data.sql; ids are fixed, not generated. Fruits are retired by setting active to false,
 * never deleted.
 */
@Entity
@Table(name = "fruit")
public class Fruit {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false)
    private boolean active;

    public Fruit() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
```

`Fruit` has no setters on purpose. Fruits are reference data, changed only with SQL (section 10.3).

### 7.4 Create `User.java`

**7.4.1** Create `app/src/main/java/org/example/user/User.java`:

```java
package org.example.user;

import java.time.Instant;

import org.hibernate.annotations.CreationTimestamp;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;

/** A user. The table is app_user because USER is a reserved word in Oracle SQL. */
@Entity
@Table(name = "app_user")
public class User {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String username;

    @Column(nullable = false, unique = true)
    private String email;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Every user has exactly one settings row, created with defaults and saved with the user. */
    @OneToOne(mappedBy = "user", cascade = CascadeType.ALL, orphanRemoval = true, optional = false)
    private UserSettings settings;

    public User() {
    }

    public User(String username, String email) {
        this.username = username;
        this.email = email;
        this.settings = new UserSettings(this);
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    /** No setter: the settings are created by the constructor and belong to this user for good. */
    public UserSettings getSettings() {
        return settings;
    }
}
```

- `GenerationType.IDENTITY` uses the identity column from step 4.2.1.
- `@CreationTimestamp` fills `created_at` when the user is first saved.
- `cascade = CascadeType.ALL` saves and deletes the settings together with the user.

### 7.5 Create `UserSettings.java`

**7.5.1** Create `app/src/main/java/org/example/user/UserSettings.java`:

```java
package org.example.user;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    public static final int DEFAULT_RESULTS_SIZE = 20;

    public static final int DEFAULT_FONT_SIZE = 12;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "results_size", nullable = false)
    private int resultsSize = DEFAULT_RESULTS_SIZE;

    @Column(name = "font_size", nullable = false)
    private int fontSize = DEFAULT_FONT_SIZE;

    /** Ordered; list index is persisted as user_settings_fruit.position. */
    @ManyToMany
    @OrderColumn(name = "position", nullable = false)
    @JoinTable(name = "user_settings_fruit",
            joinColumns = @JoinColumn(name = "user_settings_id"),
            inverseJoinColumns = @JoinColumn(name = "fruit_id"))
    private List<Fruit> fruits = new ArrayList<>();

    public UserSettings() {
    }

    /** Created by {@link User}'s constructor; defaults match schema.sql. */
    UserSettings(User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getResultsSize() {
        return resultsSize;
    }

    public void setResultsSize(int resultsSize) {
        this.resultsSize = resultsSize;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public List<Fruit> getFruits() {
        return fruits;
    }

    public void setFruits(List<Fruit> fruits) {
        this.fruits = fruits;
    }
}
```

- `@OrderColumn(name = "position")` stores each fruit's place in the list, starting at 0, in `user_settings_fruit.position`.
- `@OnDelete(action = OnDeleteAction.CASCADE)` matches the `ON DELETE CASCADE` in `schema.sql`.
- The constructor that takes a `User` is package-private, so settings are only ever created by `User`.

### 7.6 Check that it compiles

**7.6.1** Run:

```bash
./gradlew :app:compileJava
```

Expect `BUILD SUCCESSFUL`. Your IDE may show "cannot be resolved" errors until it re-imports the Gradle project. Gradle's result is the one that counts.

## 8. Test against a throwaway Oracle (`app/src/test/java/org/example/user/UserSchemaTest.java`)

### 8.1 Write the test

**8.1.1** Create `app/src/test/java/org/example/user/UserSchemaTest.java`:

```java
package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.List;

import org.example.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceException;

/**
 * schema.sql and data.sql against a real Oracle (Testcontainers). The context starting at all means Hibernate validated
 * every entity in this package against the tables. Each test's rows are rolled back.
 */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@Transactional
class UserSchemaTest {

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    @Test
    void schemaCreatesTheTables() {
        assertThat(jdbc.queryForList("SELECT LOWER(table_name) FROM user_tables", String.class))
            .contains("app_user", "fruit", "user_settings", "user_settings_fruit");
    }

    @Test
    void fruitListIsSeededInIdOrder() {
        assertThat(jdbc.queryForList("SELECT name FROM fruit WHERE active ORDER BY id", String.class))
            .containsExactly("apple", "pear", "banana", "strawberry", "melon", "orange");
    }

    @Test
    void scriptsCanRunAgainWithoutChangingTheFruitList() {
        // schema.sql first, before this test changes anything: in Oracle every DDL statement commits the open
        // transaction, so a change made before it would escape the rollback.
        run("schema.sql");

        jdbc.update("UPDATE fruit SET active = FALSE WHERE id = 2"); // a retired fruit must stay retired
        run("data.sql"); // DML only: rolled back with the test

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM fruit", Integer.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("SELECT active FROM fruit WHERE id = 2", Boolean.class)).isFalse();
    }

    private void run(String script) {
        new ResourceDatabasePopulator(new ClassPathResource(script)).execute(jdbc.getDataSource());
    }

    @Test
    void savesAUserWithDefaultSettingsAndAnOrderedFruitList() {
        User frank = new User("frank", "frank@example.com");
        frank.getSettings().getFruits().addAll(fruits(3L, 1L, 6L));
        em.persist(frank);
        em.flush();
        em.clear();

        User loaded = em.find(User.class, frank.getId());
        assertThat(loaded.getCreatedAt()).isNotNull();
        assertThat(loaded.getSettings().getResultsSize()).isEqualTo(UserSettings.DEFAULT_RESULTS_SIZE);
        assertThat(loaded.getSettings().getFontSize()).isEqualTo(UserSettings.DEFAULT_FONT_SIZE);
        assertThat(loaded.getSettings().getFruits()).extracting(Fruit::getName)
            .containsExactly("banana", "apple", "orange");
        assertThat(jdbc.queryForList("""
                SELECT usf.position FROM user_settings_fruit usf
                JOIN user_settings s ON s.id = usf.user_settings_id
                WHERE s.user_id = ? ORDER BY usf.position
                """, Integer.class, frank.getId()))
            .containsExactly(0, 1, 2);
    }

    @Test
    void usernameIsUnique() {
        em.persist(new User("gina", "gina@example.com"));
        em.flush();

        assertThatThrownBy(() -> {
            em.persist(new User("gina", "other@example.com"));
            em.flush();
        }).isInstanceOf(PersistenceException.class).hasMessageContaining("UQ_APP_USER_USERNAME");
    }

    @Test
    void deletingAUserDeletesTheirSettings() {
        User hank = new User("hank", "hank@example.com");
        hank.getSettings().getFruits().addAll(fruits(1L, 2L));
        em.persist(hank);
        em.flush();

        jdbc.update("DELETE FROM app_user WHERE id = ?", hank.getId());

        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM user_settings WHERE user_id = ?", Integer.class,
                hank.getId())).isZero();
    }

    private List<Fruit> fruits(Long... ids) {
        return Arrays.stream(ids).map(id -> em.getReference(Fruit.class, id)).toList();
    }
}
```

### 8.2 Know what each test proves

| Test | Proves |
|---|---|
| The context starting at all | `schema.sql` and `data.sql` ran without error, and Hibernate validated all three entities against the tables |
| `schemaCreatesTheTables` | All 4 tables exist |
| `fruitListIsSeededInIdOrder` | `data.sql` seeded the 6 fruits as active, in id order |
| `scriptsCanRunAgainWithoutChangingTheFruitList` | Both scripts can run again (as at every startup) without duplicating fruits or reactivating a retired one |
| `savesAUserWithDefaultSettingsAndAnOrderedFruitList` | Saving a user also saves its settings, `created_at` is filled, the defaults 20 and 12 apply, and the fruit order is kept with positions 0, 1, 2 |
| `usernameIsUnique` | The database refuses a duplicate username and names the constraint, `UQ_APP_USER_USERNAME` |
| `deletingAUserDeletesTheirSettings` | `ON DELETE CASCADE` removes the settings with the user |

### 8.3 Watch out for Oracle's DDL commits

**8.3.1** Don't run DDL (`CREATE`, `DROP`, `ALTER`) after a change inside a `@Transactional` test. In Oracle every DDL statement first commits the open transaction, so the change escapes the rollback and leaks into later tests. That is why `scriptsCanRunAgainWithoutChangingTheFruitList` runs `schema.sql` before it changes anything. In its first version, the "pear retired" update ran before `schema.sql`, got committed, and broke `fruitListIsSeededInIdOrder`.

### 8.4 Run the tests

**8.4.1** Run, with Docker running:

```bash
make test
```

Expect `BUILD SUCCESSFUL` after about a minute: `AppTest` 2 tests, `GreetingControllerTest` 1 and `UserSchemaTest` 6, all passing. Both `@SpringBootTest` classes share one Spring context, so one Oracle container serves them.

**8.4.2** Run one class at a time with:

```bash
make test TEST=org.example.user.UserSchemaTest
```

**8.4.3** Find the results in `app/build/reports/tests/test/index.html`, and the raw results with stack traces in `app/build/test-results/test/*.xml`.

## 9. Add commands for working with the database

The data commands need **no password**. They run SQL*Plus inside the container as the operating-system user `oracle` (`/ as sysdba`), then switch to the `PREFERENCES` database and schema with `ALTER SESSION`. So no password is typed, stored or shown.

### 9.1 Replace `scripts/oracle-db.sh`

**9.1.1** Replace the whole file with the finished version below. It includes the changes from sections 1.1 and 1.2.

```bash
#!/usr/bin/env bash
# Local Oracle database for preferences-service: Oracle AI Database 26ai Free in the Docker container
# preferences-oracle, published on 127.0.0.1 only, data kept in the volume preferences-oracle-data.
#
#   scripts/oracle-db.sh start     create (first time) or start the container; wait until it is ready
#   scripts/oracle-db.sh stop      stop it (data kept)
#   scripts/oracle-db.sh status    show whether it is running
#   scripts/oracle-db.sh remove    delete the container AND its data volume
#   scripts/oracle-db.sh logs      follow the container's log (Ctrl+C to stop)
#
# Working with the data (in the PREFERENCES database, as the PREFERENCES schema):
#   scripts/oracle-db.sh sql               interactive SQL*Plus (type EXIT to leave; changes are committed on EXIT)
#   scripts/oracle-db.sh query 'SQL;'      run SQL given as an argument, or on standard input; committed at the end
#   scripts/oracle-db.sh tables            the application's tables and their row counts
#   scripts/oracle-db.sh fruits            the fruit list, with active flags
#   scripts/oracle-db.sh users             users with their settings and fruit order
#   scripts/oracle-db.sh retire ID         hide fruit ID from everyone (saved orders are kept)
#   scripts/oracle-db.sh activate ID       bring fruit ID back
#   scripts/oracle-db.sh drop-tables --yes delete the 4 tables and all their data; the next start recreates and reseeds
#
# Passwords: on first use this creates ~/.config/preferences-service/oracle.env (mode 600, outside the repository)
# with random passwords for SYS/SYSTEM (ORACLE_PASSWORD) and the application login (APP_USER_PASSWORD). Docker reads
# them from that file (--env-file), so they never appear on a command line or in the output. The data commands need
# no password at all: they run SQL*Plus inside the container as the operating-system user oracle ("/ as sysdba") and
# then switch to the PREFERENCES database and schema.
set -euo pipefail

CONTAINER=${PREF_DB_CONTAINER:-preferences-oracle}
VOLUME=${CONTAINER}-data
IMAGE=${PREF_DB_IMAGE:-gvenzl/oracle-free:23.26.3-faststart}
PORT=${PREF_DB_PORT:-1525}
# The pluggable database (and service name) the application uses. The image creates it on the first start.
DATABASE=${PREF_DB_SERVICE:-PREFERENCES}
# The application login, which owns the tables (its schema has the same name).
SCHEMA=${PREF_DB_USER:-preferences}
ENV_FILE=${PREF_ENV_FILE:-$HOME/.config/preferences-service/oracle.env}

# The lines every data command starts with: stop at the first error, switch to the application's database and schema.
SESSION_SQL="WHENEVER SQLERROR EXIT FAILURE ROLLBACK
ALTER SESSION SET CONTAINER = $DATABASE;
ALTER SESSION SET CURRENT_SCHEMA = $SCHEMA;"

ensure_env_file() {
    [[ -f $ENV_FILE ]] && return
    mkdir -p "$(dirname "$ENV_FILE")"
    chmod 700 "$(dirname "$ENV_FILE")"
    (
        umask 077
        # Letters and digits only, starting with a letter (valid unquoted Oracle passwords).
        printf 'ORACLE_PASSWORD=S%s\nAPP_USER=preferences\nAPP_USER_PASSWORD=P%s\n' \
            "$(openssl rand -hex 16)" "$(openssl rand -hex 16)" > "$ENV_FILE"
    )
    echo "oracle-db: created password file $ENV_FILE"
}

exists()  { docker container inspect "$CONTAINER" >/dev/null 2>&1; }
running() { [[ $(docker container inspect -f '{{.State.Running}}' "$CONTAINER" 2>/dev/null) == true ]]; }

# Waits for this start's "ready" line. $1 is the time the container was started: older log lines (a previous start's
# "ready") are skipped. grep reads all of its input (no -q): with -q it would exit at the first match, docker logs
# would fail with a broken pipe, and pipefail would turn the match into a failure.
wait_ready() {
    local since=$1
    echo "oracle-db: waiting for the database (the first start takes about a minute)..."
    for _ in $(seq 1 180); do
        if docker logs --since "$since" "$CONTAINER" 2>&1 | grep 'DATABASE IS READY TO USE!' >/dev/null; then
            echo "oracle-db: ready on 127.0.0.1:$PORT, database (service) $DATABASE, login preferences"
            return 0
        fi
        if ! running; then
            echo "oracle-db: the container stopped; see: docker logs $CONTAINER" >&2
            return 1
        fi
        sleep 2
    done
    echo "oracle-db: not ready after 6 minutes; see: docker logs $CONTAINER" >&2
    return 1
}

require_running() {
    running || { echo "oracle-db: $CONTAINER is not running (make db-start)" >&2; exit 1; }
}

# Runs the SQL on standard input in the application's database and schema, then commits. Output formatted for reading.
run_sql() {
    require_running
    # FEEDBACK is off around the set-up lines and the COMMIT, so only the user's statements report ("2 rows selected.").
    { printf '%s\n' "SET FEEDBACK OFF" "SET LINESIZE 200" "SET PAGESIZE 100" "$SESSION_SQL" "SET FEEDBACK ON"
      cat
      printf '%s\n' "SET FEEDBACK OFF" "COMMIT;" "EXIT"; } |
        docker exec -i "$CONTAINER" sqlplus -s -l / as sysdba
}

# A fruit id must be a whole number; it goes into SQL text, so nothing else is accepted.
fruit_id() {
    [[ ${1:-} =~ ^[0-9]+$ ]] || { echo "oracle-db: give a fruit id (a whole number), for example: $0 $2 2" >&2; exit 2; }
    echo "$1"
}

case ${1:-status} in
start)
    ensure_env_file
    if running; then
        echo "oracle-db: $CONTAINER is already running"
    elif exists; then
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        docker start "$CONTAINER" >/dev/null
        wait_ready "$since"
    else
        since=$(date -u +%Y-%m-%dT%H:%M:%SZ)
        # On the first start the image creates the pluggable database ORACLE_DATABASE and, inside it, the login
        # APP_USER (and its schema). ORACLE_DATABASE is not a secret, so it can be on the command line.
        docker run -d --name "$CONTAINER" \
            -p "127.0.0.1:$PORT:1521" \
            --env-file "$ENV_FILE" \
            -e "ORACLE_DATABASE=$DATABASE" \
            -v "$VOLUME:/opt/oracle/oradata" \
            "$IMAGE" >/dev/null
        wait_ready "$since"
    fi
    ;;
stop)
    if running; then docker stop "$CONTAINER" >/dev/null; fi
    echo "oracle-db: $CONTAINER stopped"
    ;;
status)
    if running; then echo "oracle-db: $CONTAINER running on 127.0.0.1:$PORT"
    elif exists; then echo "oracle-db: $CONTAINER stopped"
    else echo "oracle-db: $CONTAINER does not exist (scripts/oracle-db.sh start creates it)"; fi
    ;;
remove)
    docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
    docker volume rm "$VOLUME" >/dev/null 2>&1 || true
    echo "oracle-db: $CONTAINER and its volume $VOLUME removed (the password file is kept)"
    ;;
logs)
    docker logs -f --tail 50 "$CONTAINER"
    ;;
sql)
    require_running
    # login.sql runs when SQL*Plus connects and switches to the application's database and schema. On Linux SQL*Plus
    # finds it through ORACLE_PATH (SQLPATH is ignored for login.sql). Errors should not end an interactive session,
    # so it leaves out WHENEVER SQLERROR.
    printf '%s\n' "ALTER SESSION SET CONTAINER = $DATABASE;" "ALTER SESSION SET CURRENT_SCHEMA = $SCHEMA;" \
        "SET LINESIZE 200 PAGESIZE 100" "SET SQLPROMPT '$DATABASE> '" |
        docker exec -i "$CONTAINER" bash -c 'mkdir -p /tmp/prefsql && cat > /tmp/prefsql/login.sql'
    echo "oracle-db: connected to database $DATABASE, schema ${SCHEMA^^}. Type EXIT to leave (changes are committed)."
    # -t only with a terminal, so the command also accepts a piped script.
    tty=-i; [[ -t 0 ]] && tty=-it
    docker exec "$tty" -e ORACLE_PATH=/tmp/prefsql "$CONTAINER" sqlplus -s -l / as sysdba
    ;;
query)
    # SQL as an argument, or (empty or missing argument) on standard input.
    if [[ -n ${2:-} ]]; then printf '%s\n' "$2" | run_sql; else run_sql; fi
    ;;
tables)
    run_sql <<'SQL'
COLUMN table_name FORMAT A25
SELECT 'APP_USER' AS table_name, COUNT(*) AS row_count FROM app_user
UNION ALL SELECT 'FRUIT', COUNT(*) FROM fruit
UNION ALL SELECT 'USER_SETTINGS', COUNT(*) FROM user_settings
UNION ALL SELECT 'USER_SETTINGS_FRUIT', COUNT(*) FROM user_settings_fruit;
SQL
    ;;
fruits)
    run_sql <<'SQL'
COLUMN name FORMAT A20
COLUMN active FORMAT A6
SELECT id, name, CASE WHEN active THEN 'yes' ELSE 'no' END AS active FROM fruit ORDER BY id;
SQL
    ;;
users)
    run_sql <<'SQL'
COLUMN username FORMAT A20
COLUMN email FORMAT A30
COLUMN fruit_order FORMAT A60
SELECT u.id, u.username, u.email, s.results_size, s.font_size,
       (SELECT LISTAGG(f.name, ', ') WITHIN GROUP (ORDER BY usf.position)
        FROM user_settings_fruit usf JOIN fruit f ON f.id = usf.fruit_id
        WHERE usf.user_settings_id = s.id) AS fruit_order
FROM app_user u JOIN user_settings s ON s.user_id = u.id
ORDER BY u.id;
SQL
    ;;
retire)
    id=$(fruit_id "${2:-}" retire)
    printf '%s\n' "UPDATE fruit SET active = FALSE WHERE id = $id;" | run_sql
    ;;
activate)
    id=$(fruit_id "${2:-}" activate)
    printf '%s\n' "UPDATE fruit SET active = TRUE WHERE id = $id;" | run_sql
    ;;
drop-tables)
    if [[ ${2:-} != --yes ]]; then
        echo "oracle-db: this deletes the tables app_user, fruit, user_settings and user_settings_fruit and ALL their" >&2
        echo "data. Run again with --yes (make db-drop-tables CONFIRM=yes). The next start recreates and reseeds them." >&2
        exit 2
    fi
    # Children first, so no foreign key is in the way; IF EXISTS makes a second run harmless.
    run_sql <<'SQL'
DROP TABLE IF EXISTS user_settings_fruit PURGE;
DROP TABLE IF EXISTS user_settings PURGE;
DROP TABLE IF EXISTS fruit PURGE;
DROP TABLE IF EXISTS app_user PURGE;
SQL
    ;;
*)
    echo "usage: $0 start|stop|status|remove|logs|sql|query|tables|fruits|users|retire ID|activate ID|drop-tables --yes" >&2
    exit 2
    ;;
esac
```

**9.1.2** Keep it executable:

```bash
chmod +x scripts/oracle-db.sh
bash -n scripts/oracle-db.sh     # syntax check: prints nothing when the file is fine
```

### 9.2 Know how the script works

**9.2.1** `run_sql` wraps the SQL it reads on standard input. It first turns off feedback and switches to the `PREFERENCES` database and schema. It then runs your SQL with feedback on, and ends with `COMMIT` and `EXIT`. `WHENEVER SQLERROR EXIT FAILURE ROLLBACK` stops at the first error and undoes that command's changes.

**9.2.2** `sql` (interactive) writes a `login.sql` into the container, which runs when SQL*Plus connects. **On Linux, SQL*Plus finds `login.sql` through `ORACLE_PATH`.** `SQLPATH` doesn't work for it: the first version used `SQLPATH`, and the session stayed in the root container as `SYS`. `-t` is added only when there's a terminal, so a script can also be piped into `sql`.

**9.2.3** `retire` and `activate` accept only digits. The id goes into SQL text, so anything else, for example `2; DROP TABLE fruit`, is refused before it reaches the database.

**9.2.4** `drop-tables` refuses to run without `--yes`.

### 9.3 Replace the `Makefile`

**9.3.1** Replace the whole file with the version below. Recipe lines must start with a **tab**, not spaces.

```make
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
```

**9.3.2** `export SQL FRUIT` hands those two values to the script as environment variables. The recipe reads them as `"$$SQL"` (in a recipe, `$$` is a literal `$`). A query with quotes in it, such as `WHERE name LIKE '%an%'`, then arrives unchanged.

**9.3.3** List all targets with:

```bash
make
```

## 10. Use the database

### 10.1 Look at the data

| Command | Shows |
|---|---|
| `make db-status` | Whether the container is running |
| `make db-check` | Tables with row counts, then the fruit list |
| `make db-tables` | Row counts of the 4 tables |
| `make db-fruits` | Each fruit's id, name and active flag |
| `make db-users` | Each user with result size, font size and fruit order |
| `make db-logs` | The container's log, followed (Ctrl+C stops) |

**10.1.1** On a fresh database, `make db-fruits` shows:

```
        ID NAME                 ACTIVE
---------- -------------------- ------
         1 apple                yes
         2 pear                 yes
         3 banana               yes
         4 strawberry           yes
         5 melon                yes
         6 orange               yes

6 rows selected.
```

### 10.2 Run your own SQL

**10.2.1** Run one statement:

```bash
make db-query SQL="SELECT name FROM fruit WHERE name LIKE '%an%' ORDER BY id;"
```

**10.2.2** Run several statements or a file:

```bash
make db-query < my-changes.sql
echo "SELECT COUNT(*) FROM app_user;" | make db-query
```

**10.2.3** Work interactively:

```bash
make db-sql
```

The prompt is `PREFERENCES>`. Type SQL that ends with `;`, and `EXIT` to leave. SQL*Plus commits on `EXIT`, so type `ROLLBACK;` first to throw away experiments.

**10.2.4** Write table names without a schema, such as `fruit` or `app_user`. Every command has already switched to the `PREFERENCES` schema.

**10.2.5** Know that every `db-query` commits at the end, and stops and rolls back at the first error.

### 10.3 Manage the fruit list

| Change | How | Effect on users |
|---|---|---|
| Retire | `make db-retire FRUIT=2` | Hidden from everyone; saved orders keep it |
| Bring back | `make db-activate FRUIT=2` | Comes back where each user had it |
| Add | Add a row with a **new** id to `data.sql`, then restart the service | The next startup inserts it |

**10.3.1** Never delete a fruit or reuse an id: saved orders refer to fruits by id.

**10.3.2** `data.sql` never reactivates a retired fruit, because its `MERGE` only inserts missing ids.

### 10.4 Add a user by hand

There is no API for users yet. Add one with SQL to try things out. The database fills in the default settings:

```bash
make db-query SQL="INSERT INTO app_user (username, email, created_at) VALUES ('frank', 'frank@example.com', SYSTIMESTAMP);
INSERT INTO user_settings (user_id) SELECT id FROM app_user WHERE username = 'frank';
INSERT INTO user_settings_fruit (user_settings_id, position, fruit_id)
  SELECT s.id, v.pos, v.fid FROM user_settings s JOIN app_user u ON u.id = s.user_id
  CROSS JOIN (SELECT 0 pos, 3 fid FROM dual UNION ALL SELECT 1, 1 FROM dual) v
  WHERE u.username = 'frank';"
make db-users
```

Expect: `1 frank frank@example.com 20 12 banana, apple`.

**10.4.1** Remove the user again. The settings and fruit rows go with it, through `ON DELETE CASCADE`:

```bash
make db-query SQL="DELETE FROM app_user WHERE username = 'frank';"
```

### 10.5 Reset the data

| Goal | Command | Deletes |
|---|---|---|
| Empty tables, freshly seeded | `make db-drop-tables CONFIRM=yes`, then `make run` | The 4 tables and their rows. The next startup recreates and reseeds them |
| A brand-new database | `make db-recreate` | The whole container and its data volume. The password file is kept |
| Stop for now | `make db-stop` | Nothing |

## 11. Verify the whole thing

### 11.1 Run the tests

**11.1.1** Run `make test`. Expect 9 tests in 3 classes, all passing (section 8.4).

### 11.2 Check the first startup on the local database

**11.2.1** Run:

```bash
make db-start
make run          # or: make run-jar
```

**11.2.2** Expect `Started App in ... seconds` in the log, with no `Schema-validation` error. In a second terminal, check:

```bash
curl -s localhost:8080/actuator/health      # {"groups":["liveness","readiness"],"status":"UP"}
make db-check                               # APP_USER 0, FRUIT 6, USER_SETTINGS 0, USER_SETTINGS_FRUIT 0; 6 fruits
```

### 11.3 Check that startup can run again

**11.3.1** Stop the service with Ctrl+C and start it again with `make run`. Expect it to start, and `make db-tables` still to show `FRUIT 6`, with no duplicates.

### 11.4 Check that the database survives a restart

**11.4.1** Run:

```bash
make db-restart
```

Expect `ready ...` within seconds, not minutes. `PREFERENCES` opens again on its own; check it with `make db-tables`.

### 11.5 Check a full rebuild

**11.5.1** Run:

```bash
make db-drop-tables CONFIRM=yes
make run          # recreates the 4 tables and the 6 fruits
make db-check
```

### 11.6 Check that no password leaks

**11.6.1** Search the output for the password variable names:

```bash
make run-jar 2>&1 | grep -c 'PREF_DB_PASSWORD\|APP_USER_PASSWORD'    # 0 (stop with Ctrl+C)
```

## 12. Fix common problems

| Symptom | Cause | Fix |
|---|---|---|
| `ORA-12514` (unknown service) at startup | The container was created before `ORACLE_DATABASE`, so it has no `PREFERENCES` database | `make db-recreate` (deletes its data) |
| `Schema-validation: missing table [app_user]` | `spring.sql.init.mode` isn't `always`, or `defer-datasource-initialization=true` is set | Section 6.1 |
| `Schema-validation: wrong column type ...` | An entity and `schema.sql` disagree | Make them match; don't change `ddl-auto` |
| `ORA-00903: invalid table name` | A table named `user` | Use `app_user` (step 4.1) |
| A test passes alone but fails after another | A DDL statement committed a test's change | Section 8.3 |
| `make db-start` takes minutes after a restart | The old `wait_ready` with `grep -q` | Section 1.2 |
| `make db-sql` works as `SYS` in `CDB$ROOT` | `login.sql` was looked up through `SQLPATH` | Use `ORACLE_PATH` (step 9.2.2) |
| `ORA-00942: table or view ... does not exist` from `make db-query` | The tables were dropped and not recreated yet | `make run` once |
| `make: *** missing separator` | A recipe line starts with spaces | Start recipe lines with a tab |
| `Could not resolve placeholder 'PREF_DB_PASSWORD'` | The service was started without the password | Use `make run` or `make run-jar` |
| `oracle-db: preferences-oracle is not running` | The container is stopped | `make db-start` |

## 13. Next steps

### 13.1 Add data access

**13.1.1** Copy the repositories from user-config-service, `UserRepository`, `FruitRepository` and `UserSettingsRepository`, into `org.example.user`.

**13.1.2** Then bring over `UserService` and `UserSettingsController` to expose the settings over HTTP.

**13.1.3** Change any native SQL that names the table `user` to `app_user`. In user-config-service only the tests do this.

### 13.2 Manage schema changes

**13.2.1** Know that `CREATE TABLE IF NOT EXISTS` never changes an existing table. Once the schema starts changing, move to a migration tool such as Flyway.
