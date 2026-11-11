package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.example.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import jakarta.persistence.EntityManager;

/** GET /users/{userId}/settings and POST /users/{userId}/fruits, including fruits being added and retired. */
@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional // roll back each test's data, including added or retired fruits
class FruitOrderControllerTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    long ginaId;

    @BeforeEach
    void createUser() {
        ginaId = userService.createUser("gina", "gina@example.com").getId();
    }

    @Test
    void settingsShowDefaults() throws Exception {
        mvc.perform(get("/users/" + ginaId + "/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(ginaId))
            .andExpect(jsonPath("$.username").value("gina"))
            .andExpect(jsonPath("$.result_size").value(20))
            .andExpect(jsonPath("$.font_size").value(12))
            .andExpect(jsonPath("$.fruits[*].id", contains(1, 2, 3, 4, 5, 6)))
            .andExpect(jsonPath("$.fruits[*].name",
                    contains("apple", "pear", "banana", "strawberry", "melon", "orange")));
    }

    @Test
    void reordersFruits() throws Exception {
        postFruits("[3, 1, 6, 2, 5, 4]")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("gina"))
            .andExpect(jsonPath("$.fruits[*].id", contains(3, 1, 6, 2, 5, 4)))
            .andExpect(jsonPath("$.fruits[0].name").value("banana"));

        assertThat(storedFruitIds()).containsExactly(3L, 1L, 6L, 2L, 5L, 4L);
        expectDisplayedIds(3, 1, 6, 2, 5, 4);
    }

    @Test
    void rejectsBadRequests() throws Exception {
        postFruits("[1, 1, 2, 3, 4, 5]").andExpect(status().isBadRequest()); // duplicate
        postFruits("[1, 2, 3, 4, 5, 99]").andExpect(status().isBadRequest()); // unknown id
        postFruits("[]").andExpect(status().isBadRequest());
        postJson("/users/" + ginaId + "/fruits", "{}").andExpect(status().isBadRequest());
        postJson("/users/" + ginaId + "/fruits", "{\"fruit_ids\": [1, null]}").andExpect(status().isBadRequest());

        assertThat(storedFruitIds()).containsExactly(1L, 2L, 3L, 4L, 5L, 6L); // unchanged
    }

    @Test
    void missingFruitIs409() throws Exception {
        postFruits("[1, 2, 3, 4, 5]").andExpect(status().isConflict());
    }

    @Test
    void unknownUserIs404() throws Exception {
        postJson("/users/999999/fruits", "{\"fruit_ids\": [1, 2, 3, 4, 5, 6]}").andExpect(status().isNotFound());
        mvc.perform(get("/users/999999/settings")).andExpect(status().isNotFound());
    }

    @Test
    void retiredFruitIsHidden() throws Exception {
        setActive(2, false); // pear

        expectDisplayedIds(1, 3, 4, 5, 6);
        postFruits("[6, 5, 4, 3, 2, 1]").andExpect(status().isConflict()); // still sends pear
        postFruits("[6, 5, 4, 3, 1]")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fruits[*].id", contains(6, 5, 4, 3, 1)));
    }

    @Test
    void newFruitIsAppendedForExistingUsers() throws Exception {
        addFruit(7, "kiwi");

        expectDisplayedIds(1, 2, 3, 4, 5, 6, 7);
        postFruits("[6, 5, 4, 3, 2, 1]").andExpect(status().isConflict()); // page loaded before kiwi
        postFruits("[7, 6, 5, 4, 3, 2, 1]").andExpect(status().isOk());
        assertThat(storedFruitIds()).containsExactly(7L, 6L, 5L, 4L, 3L, 2L, 1L);
    }

    @Test
    void reactivatedFruitReturnsToItsSavedPlace() throws Exception {
        postFruits("[3, 1, 6, 2, 5, 4]").andExpect(status().isOk());
        setActive(6, false); // orange, saved in third place

        postFruits("[4, 5, 2, 1, 3]").andExpect(status().isOk());
        assertThat(storedFruitIds()).containsExactly(4L, 5L, 6L, 2L, 1L, 3L); // orange kept its slot

        setActive(6, true);
        expectDisplayedIds(4, 5, 6, 2, 1, 3);
    }

    private ResultActions postFruits(String ids) throws Exception {
        return postJson("/users/" + ginaId + "/fruits", "{\"fruit_ids\": " + ids + "}");
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    private void expectDisplayedIds(Integer... ids) throws Exception {
        mvc.perform(get("/users/" + ginaId + "/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.fruits[*].id", contains(ids)));
    }

    /** gina's saved list straight from the join table, retired fruits included. */
    private List<Long> storedFruitIds() {
        em.flush();
        return jdbc.queryForList("""
                SELECT usf.fruit_id
                FROM app_user u
                JOIN user_settings s ON s.user_id = u.id
                JOIN user_settings_fruit usf ON usf.user_settings_id = s.id
                WHERE u.username = 'gina'
                ORDER BY usf.position
                """, Long.class);
    }

    // Fruit changes go through SQL, as an admin would make them. Flush pending JPA writes
    // first, and clear afterwards so JPA doesn't keep using stale Fruit objects.

    private void setActive(long fruitId, boolean active) {
        em.flush();
        jdbc.update("UPDATE fruit SET active = ? WHERE id = ?", active, fruitId);
        em.clear();
    }

    private void addFruit(long id, String name) {
        em.flush();
        jdbc.update("INSERT INTO fruit (id, name) VALUES (?, ?)", id, name);
        em.clear();
    }

}
