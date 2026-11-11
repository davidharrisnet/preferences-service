package org.example.user;

public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long userId) {
        super("No user with id " + userId);
    }

}
