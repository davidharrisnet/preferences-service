package org.example.user;

/** The submitted fruit order doesn't match the current set of active fruits (mapped to 409). */
public class FruitListOutOfDateException extends RuntimeException {

    public FruitListOutOfDateException() {
        super("fruit_ids must contain every active fruit exactly once; the fruit list has changed, reload and try again");
    }

}
