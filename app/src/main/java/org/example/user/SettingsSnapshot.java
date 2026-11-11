package org.example.user;

import java.util.List;

/**
 * A user's settings, fully loaded inside the service transaction. {@code fruits} is the
 * display order: retired fruits removed, newly added ones appended.
 */
public record SettingsSnapshot(long userId, String username, int resultSize, int fontSize, List<Fruit> fruits) {
}
