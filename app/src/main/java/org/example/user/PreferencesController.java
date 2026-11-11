package org.example.user;

import java.net.URI;
import java.time.Instant;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.fasterxml.jackson.annotation.JsonProperty;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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

    // ---- JSON shapes -------------------------------------------------------------

    record FruitView(@Schema(example = "3") long id, @Schema(example = "banana") String name) {

        static List<FruitView> of(List<Fruit> fruits) {
            return fruits.stream().map(f -> new FruitView(f.getId(), f.getName())).toList();
        }

    }

    @Schema(description = "A user's settings; fruits are in the user's display order")
    record SettingsResponse(@JsonProperty("user_id") @Schema(example = "1") long userId,
            @Schema(example = "frank") String username,
            @JsonProperty("result_size") @Schema(example = "20") int resultSize,
            @JsonProperty("font_size") @Schema(example = "12") int fontSize, List<FruitView> fruits) {

        static SettingsResponse of(SettingsSnapshot s) {
            return new SettingsResponse(s.userId(), s.username(), s.resultSize(), s.fontSize(),
                    FruitView.of(s.fruits()));
        }

    }

    record CreateUserRequest(@Schema(example = "frank") String username,
            @Schema(example = "frank@example.com") String email) {
    }

    record ResultSizeRequest(@JsonProperty("result_size") @Schema(example = "35") Integer resultSize) {
    }

    record FontSizeRequest(@JsonProperty("font_size") @Schema(example = "16") Integer fontSize) {
    }

    @Schema(example = "{\"fruit_ids\": [3, 1, 6, 2, 5, 4]}")
    record FruitOrderRequest(@JsonProperty("fruit_ids")
            @Schema(description = "Every active fruit id exactly once, in the new order") List<Long> fruitIds) {
    }

    record UserListItem(@JsonProperty("user_id") @Schema(example = "1") long userId,
            @Schema(example = "frank") String username, @Schema(example = "frank@example.com") String email,
            @JsonProperty("created_at") @Schema(example = "2026-11-09T19:57:28Z") Instant createdAt) {

        static UserListItem of(UserSummary u) {
            return new UserListItem(u.id(), u.username(), u.email(), u.createdAt());
        }

    }

    // ---- Endpoints -----------------------------------------------------------------

    @GetMapping
    @Operation(summary = "List all users", description = "Ordered by user id. Settings are not included.")
    @ApiResponse(responseCode = "200", description = "OK")
    public List<UserListItem> listUsers() {
        return userService.listUsers().stream().map(UserListItem::of).toList();
    }

    @PostMapping
    @Operation(summary = "Create a user with default settings",
            description = "result_size 20, font_size 12, and every active fruit in id order.")
    @ApiResponse(responseCode = "201", description = "Created")
    @ApiResponse(responseCode = "400", description = "username or email missing")
    @ApiResponse(responseCode = "409", description = "username or email already taken")
    public ResponseEntity<SettingsResponse> createUser(@RequestBody CreateUserRequest request) {
        User user = userService.createUser(request.username(), request.email());
        SettingsResponse body = SettingsResponse.of(userService.getSettings(user.getId()));
        return ResponseEntity.created(URI.create("/users/" + user.getId() + "/settings")).body(body);
    }

    @GetMapping("/{userId}/settings")
    @Operation(summary = "Get a user's settings")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "404", description = "No user with this id")
    public SettingsResponse getSettings(@Parameter(example = "1") @PathVariable Long userId) {
        return SettingsResponse.of(userService.getSettings(userId));
    }

    @PostMapping("/{userId}/result_size")
    @Operation(summary = "Update a user's result size")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "400", description = "result_size missing, not a number, or less than 1")
    @ApiResponse(responseCode = "404", description = "No user with this id")
    public SettingsResponse updateResultSize(@Parameter(example = "1") @PathVariable Long userId,
            @RequestBody ResultSizeRequest request) {
        if (request.resultSize() == null) {
            throw new IllegalArgumentException("result_size is required");
        }
        return SettingsResponse.of(userService.updateResultSize(userId, request.resultSize()));
    }

    @PostMapping("/{userId}/font_size")
    @Operation(summary = "Update a user's font size")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "400", description = "font_size missing, not a number, or less than 1")
    @ApiResponse(responseCode = "404", description = "No user with this id")
    public SettingsResponse updateFontSize(@Parameter(example = "1") @PathVariable Long userId,
            @RequestBody FontSizeRequest request) {
        if (request.fontSize() == null) {
            throw new IllegalArgumentException("font_size is required");
        }
        return SettingsResponse.of(userService.updateFontSize(userId, request.fontSize()));
    }

    @PostMapping("/{userId}/fruits")
    @Operation(summary = "Update a user's fruit order",
            description = "fruit_ids must contain every active fruit exactly once.")
    @ApiResponse(responseCode = "200", description = "OK")
    @ApiResponse(responseCode = "400", description = "Empty, null, duplicate, or unknown fruit ids")
    @ApiResponse(responseCode = "404", description = "No user with this id")
    @ApiResponse(responseCode = "409",
            description = "fruit_ids doesn't match the current active fruits (one was added or retired); reload")
    public SettingsResponse updateFruitOrder(@Parameter(example = "1") @PathVariable Long userId,
            @RequestBody FruitOrderRequest request) {
        return SettingsResponse.of(userService.updateFruitOrder(userId, request.fruitIds()));
    }

    // ---- Errors (RFC 9457 problem details) ----------------------------------------

    @ExceptionHandler({ FruitListOutOfDateException.class, UserAlreadyExistsException.class })
    ProblemDetail conflict(RuntimeException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
    }

    @ExceptionHandler(UserNotFoundException.class)
    ProblemDetail userNotFound(UserNotFoundException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail badRequest(IllegalArgumentException ex) {
        return ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
    }

}
