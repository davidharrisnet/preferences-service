package org.example.user;

/** Username or email is already taken (mapped to 409). */
public class UserAlreadyExistsException extends RuntimeException {

    public UserAlreadyExistsException(String field, String value) {
        super("A user with " + field + " '" + value + "' already exists");
    }

}
