package com.overwatch.cli.command;

import com.overwatch.cli.Api;
import com.overwatch.cli.Ow;
import picocli.CommandLine;
import picocli.CommandLine.Spec;

import java.util.concurrent.Callable;

/**
 * What every subcommand needs: the API client and the global flags.
 *
 * <p>The root is found by walking up the command tree rather than injected with
 * {@code @ParentCommand}. That annotation gives the *immediate* parent, which
 * for {@code ow alerts list} is {@code AlertsCommand} rather than {@code Ow} —
 * so a nested command would have to reach through its own parent to get at the
 * options, and every new level of nesting would add a hop. Walking to the root
 * is depth-independent, which means a command can be moved or nested without
 * touching how it reads its configuration.
 *
 * <p>{@link Callable} of Integer rather than Runnable, because the exit code is
 * part of a CLI's interface: {@code ow status} returning non-zero when a
 * service is down is what lets it be used in a health check or a CI gate.
 */
public abstract class Base implements Callable<Integer> {

    @Spec
    private CommandLine.Model.CommandSpec spec;

    protected Ow root() {
        CommandLine current = spec.commandLine();
        while (current.getParent() != null) current = current.getParent();
        return (Ow) current.getCommandSpec().userObject();
    }

    protected Api api() {
        return root().api();
    }

    /** True when the user asked for data only — no headings, notes or hints. */
    protected boolean quiet() {
        return root().quiet();
    }

    protected CommandLine.Model.CommandSpec spec() {
        return spec;
    }

    /** Print this command's usage — what a command with required subcommands does. */
    protected int usage() {
        spec.commandLine().usage(System.out);
        return 0;
    }
}
