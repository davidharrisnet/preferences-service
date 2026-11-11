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
