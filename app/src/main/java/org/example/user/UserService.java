package org.example.user;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class UserService {

    private final UserRepository users;

    private final FruitRepository fruits;

    public UserService(UserRepository users, FruitRepository fruits) {
        this.users = users;
        this.fruits = fruits;
    }

    /**
     * Creates a user with default settings: results size 20, font size 12, and every
     * active fruit in data.sql order.
     *
     * @throws IllegalArgumentException if username or email is blank
     * @throws UserAlreadyExistsException if the username or email is taken
     */
    @Transactional
    public User createUser(String username, String email) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username is required");
        }
        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("email is required");
        }
        if (users.existsByUsername(username)) {
            throw new UserAlreadyExistsException("username", username);
        }
        if (users.existsByEmail(email)) {
            throw new UserAlreadyExistsException("email", email);
        }
        User user = new User(username, email);
        user.getSettings().getFruits().addAll(fruits.findAllByActiveTrueOrderByIdAsc());
        return users.save(user);
    }

    /** All users, ordered by id. */
    @Transactional(readOnly = true)
    public List<UserSummary> listUsers() {
        return users.findAllByOrderByIdAsc();
    }

    /**
     * The user's settings with fruits in display order: their saved order with retired fruits
     * left out, followed by any active fruits added since they last saved (in id order).
     *
     * @throws UserNotFoundException if no user has this id
     */
    @Transactional(readOnly = true)
    public SettingsSnapshot getSettings(Long userId) {
        return snapshot(settingsFor(userId));
    }

    /**
     * @throws UserNotFoundException if no user has this id
     * @throws IllegalArgumentException if resultSize is not positive
     */
    @Transactional
    public SettingsSnapshot updateResultSize(Long userId, int resultSize) {
        requirePositive("result_size", resultSize);
        UserSettings settings = settingsFor(userId);
        settings.setResultsSize(resultSize);
        return snapshot(settings);
    }

    /**
     * @throws UserNotFoundException if no user has this id
     * @throws IllegalArgumentException if fontSize is not positive
     */
    @Transactional
    public SettingsSnapshot updateFontSize(Long userId, int fontSize) {
        requirePositive("font_size", fontSize);
        UserSettings settings = settingsFor(userId);
        settings.setFontSize(fontSize);
        return snapshot(settings);
    }

    /**
     * Saves a new fruit order. {@code fruitIds} must contain every active fruit exactly
     * once. Retired fruits in the saved list keep their slots, so a fruit that is
     * reactivated reappears where the user had it.
     *
     * @throws UserNotFoundException if no user has this id
     * @throws IllegalArgumentException if fruitIds is empty, has nulls or duplicates, or
     * contains an id that doesn't exist
     * @throws FruitListOutOfDateException if fruitIds isn't exactly the set of active
     * fruits (e.g. a fruit was added or retired since the page loaded)
     */
    @Transactional
    public SettingsSnapshot updateFruitOrder(Long userId, List<Long> fruitIds) {
        if (fruitIds == null || fruitIds.isEmpty()) {
            throw new IllegalArgumentException("fruit_ids is required");
        }
        if (fruitIds.contains(null)) {
            throw new IllegalArgumentException("fruit_ids must not contain null");
        }
        if (new HashSet<>(fruitIds).size() != fruitIds.size()) {
            throw new IllegalArgumentException("fruit_ids must not contain duplicates");
        }
        UserSettings settings = settingsFor(userId);

        Set<Long> existing = fruits.findAllById(fruitIds).stream().map(Fruit::getId).collect(Collectors.toSet());
        List<Long> unknown = fruitIds.stream().filter(id -> !existing.contains(id)).toList();
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("unknown fruit ids: " + unknown);
        }

        Map<Long, Fruit> active = fruits.findAllByActiveTrueOrderByIdAsc().stream()
            .collect(Collectors.toMap(Fruit::getId, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        if (!active.keySet().equals(new HashSet<>(fruitIds))) {
            throw new FruitListOutOfDateException();
        }

        // Fill the saved list's active slots with the new order; retired fruits stay put.
        Iterator<Long> next = fruitIds.iterator();
        List<Fruit> merged = new ArrayList<>();
        for (Fruit saved : settings.getFruits()) {
            merged.add(saved.isActive() ? active.get(next.next()) : saved);
        }
        next.forEachRemaining(id -> merged.add(active.get(id)));

        settings.getFruits().clear();
        settings.getFruits().addAll(merged);
        return snapshot(settings);
    }

    private SettingsSnapshot snapshot(UserSettings settings) {
        User user = settings.getUser();
        return new SettingsSnapshot(user.getId(), user.getUsername(), settings.getResultsSize(),
                settings.getFontSize(), displayOrder(settings));
    }

    private List<Fruit> displayOrder(UserSettings settings) {
        List<Fruit> result = new ArrayList<>();
        Set<Long> seen = new HashSet<>();
        for (Fruit fruit : settings.getFruits()) {
            if (fruit.isActive()) {
                result.add(fruit);
                seen.add(fruit.getId());
            }
        }
        for (Fruit fruit : fruits.findAllByActiveTrueOrderByIdAsc()) {
            if (!seen.contains(fruit.getId())) {
                result.add(fruit);
            }
        }
        return result;
    }

    private UserSettings settingsFor(Long userId) {
        return users.findById(Objects.requireNonNull(userId))
            .orElseThrow(() -> new UserNotFoundException(userId))
            .getSettings();
    }

    private static void requirePositive(String field, int value) {
        if (value < 1) {
            throw new IllegalArgumentException(field + " must be a positive integer");
        }
    }

}
