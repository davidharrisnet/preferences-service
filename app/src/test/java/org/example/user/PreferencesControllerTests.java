package org.example.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.example.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional // roll back each test's data
class PreferencesControllerTests {

    @Autowired
    MockMvc mvc;

    @Autowired
    UserService userService;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    EntityManager em;

    long frankId;

    @BeforeEach
    void createUser() {
        frankId = userService.createUser("frank", "frank@example.com").getId();
    }

    @Test
    void updatesResultSize() throws Exception {
        postJson("/users/" + frankId + "/result_size", "{\"result_size\": 35}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(frankId))
            .andExpect(jsonPath("$.username").value("frank"))
            .andExpect(jsonPath("$.result_size").value(35));

        assertThat(storedSetting("results_size")).isEqualTo(35);
        assertThat(storedSetting("font_size")).isEqualTo(12); // untouched
    }

    @Test
    void updatesFontSize() throws Exception {
        postJson("/users/" + frankId + "/font_size", "{\"font_size\": 18}")
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.user_id").value(frankId))
            .andExpect(jsonPath("$.username").value("frank"))
            .andExpect(jsonPath("$.font_size").value(18));

        assertThat(storedSetting("font_size")).isEqualTo(18);
        assertThat(storedSetting("results_size")).isEqualTo(20); // untouched
    }

    @ParameterizedTest
    @ValueSource(strings = { "result_size", "font_size" })
    void unknownUserIs404(String field) throws Exception {
        postJson("/users/999999/" + field, "{\"" + field + "\": 35}")
            .andExpect(status().isNotFound());
    }

    @ParameterizedTest
    @ValueSource(strings = { "result_size", "font_size" })
    void missingValueIs400(String field) throws Exception {
        postJson("/users/" + frankId + "/" + field, "{}")
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = { "result_size", "font_size" })
    void nonPositiveValueIs400(String field) throws Exception {
        postJson("/users/" + frankId + "/" + field, "{\"" + field + "\": 0}")
            .andExpect(status().isBadRequest());
    }

    @ParameterizedTest
    @ValueSource(strings = { "result_size", "font_size" })
    void nonNumericValueIs400(String field) throws Exception {
        postJson("/users/" + frankId + "/" + field, "{\"" + field + "\": \"big\"}")
            .andExpect(status().isBadRequest());
    }

    private ResultActions postJson(String path, String body) throws Exception {
        return mvc.perform(post(path).contentType(MediaType.APPLICATION_JSON).content(body));
    }

    /** Reads frank's stored value straight from the table, bypassing JPA. */
    private Integer storedSetting(String column) {
        em.flush(); // test transaction is still open; write pending changes before checking with SQL
        return jdbc.queryForObject("SELECT s." + column + """
                 FROM user_settings s
                JOIN app_user u ON u.id = s.user_id WHERE u.username = 'frank'
                """, Integer.class);
    }

}
