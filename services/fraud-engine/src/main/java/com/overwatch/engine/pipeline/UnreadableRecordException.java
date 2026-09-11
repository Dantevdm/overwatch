package com.overwatch.engine.pipeline;

/**
 * A record arrived that could not be turned into a transaction.
 *
 * <p>Thrown rather than swallowed so the record reaches the dead-letter topic.
 * It exists as its own type for one reason: the error handler has to be able to
 * tell "this will never work" from "this might work in a second", and retrying a
 * message that cannot be parsed spends the partition's time to reach the same
 * answer three times.
 */
public class UnreadableRecordException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public UnreadableRecordException(String message) {
        super(message);
    }
}
