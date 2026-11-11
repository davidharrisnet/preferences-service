package org.example.user;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInRelativeOrder;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.example.TestcontainersConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.transaction.annotation.Transactional;

import com.jayway.jsonpath.JsonPath;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@AutoConfigureMockMvc
@Transactional
class CreateUserControllerTests {

    @Autowired
    MockMvc mvc;

    @Test
    void createsUserWithDefaultSettings() throws Exception {
        String body = createUser("{\"username\": \"hank\", \"email\": \"hank@example.com\"}")
            .andExpect(status().isCreated())
            .andExpect(header().string("Location", matchesPattern("/users/\\d+/settings")))
            .andExpect(jsonPath("$.username").value("hank"))
            .andExpect(jsonPath("$.result_size").value(20))
            .andExpect(jsonPath("$.font_size").value(12))
            .andExpect(jsonPath("$.fruits[*].id", contains(1, 2, 3, 4, 5, 6)))
            .andReturn().getResponse().getContentAsString();

        Number userId = JsonPath.read(body, "$.user_id");
        mvc.perform(get("/users/" + userId + "/settings"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.username").value("hank"));
    }

    @Test
    void missingFieldsAre400() throws Exception {
        createUser("{\"email\": \"x@example.com\"}").andExpect(status().isBadRequest());
        createUser("{\"username\": \"x\"}").andExpect(status().isBadRequest());
        createUser("{\"username\": \" \", \"email\": \"x@example.com\"}").andExpect(status().isBadRequest());
    }

    @Test
    void duplicateUsernameOrEmailIs409() throws Exception {
        createUser("{\"username\": \"ivy\", \"email\": \"ivy@example.com\"}").andExpect(status().isCreated());
        createUser("{\"username\": \"ivy\", \"email\": \"other@example.com\"}").andExpect(status().isConflict());
        createUser("{\"username\": \"other\", \"email\": \"ivy@example.com\"}").andExpect(status().isConflict());
    }

    @Test
    void listsUsersInIdOrder() throws Exception {
        createUser("{\"username\": \"jack\", \"email\": \"jack@example.com\"}").andExpect(status().isCreated());
        createUser("{\"username\": \"kate\", \"email\": \"kate@example.com\"}").andExpect(status().isCreated());

        mvc.perform(get("/users"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$[*].username", containsInRelativeOrder("jack", "kate")))
            .andExpect(jsonPath("$[?(@.username == 'jack')].email", contains("jack@example.com")))
            .andExpect(jsonPath("$[?(@.username == 'jack')].user_id", contains(notNullValue())))
            .andExpect(jsonPath("$[?(@.username == 'jack')].created_at", contains(notNullValue())))
            .andExpect(jsonPath("$[0].fruits").doesNotExist()); // settings aren't part of the list
    }

    private ResultActions createUser(String json) throws Exception {
        return mvc.perform(post("/users").contentType(MediaType.APPLICATION_JSON).content(json));
    }

}
