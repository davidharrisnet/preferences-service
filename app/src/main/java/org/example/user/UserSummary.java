package org.example.user;

import java.time.Instant;

/** A user without their settings; filled directly by a repository query (no entity loading). */
public record UserSummary(Long id, String username, String email, Instant createdAt) {
}
