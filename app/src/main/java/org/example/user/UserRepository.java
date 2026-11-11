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
