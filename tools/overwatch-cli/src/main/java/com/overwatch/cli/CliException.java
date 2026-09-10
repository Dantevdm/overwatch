package com.overwatch.cli;

/**
 * A failure the user should read as a sentence, not a stack trace.
 *
 * <p>Anything thrown as one of these is printed as a single line and the
 * process exits non-zero. Everything else — a genuine bug in here — keeps its
 * stack trace, because that is what a bug needs and hiding it would make this
 * tool undebuggable.
 */
public class CliException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public CliException(String message) {
        super(message);
    }
}
