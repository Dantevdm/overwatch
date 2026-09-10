package com.overwatch.cli;

/**
 * Colour and text styling, and the decision about whether to use any.
 *
 * <p>Colour is off unless the output is a terminal. A CLI whose output is piped
 * into {@code grep} or captured by a script must emit plain text, or every
 * comparison in that script is against a string full of escape sequences —
 * which is how a tool ends up "working interactively but not in CI". The check
 * is {@link System#console()}, which is null when stdout is redirected.
 *
 * <p>{@code NO_COLOR} is honoured because it is the de facto standard and costs
 * one line, and {@code FORCE_COLOR} because the one case the console check gets
 * wrong is a deliberate pipe into a pager that does understand colour.
 */
public final class Ansi {

    // Written as a unicode escape rather than a literal ESC byte: an
    // invisible control character in source is invisible in a diff too.
    private static final String ESC = "\u001B";
    private static final String RESET = ESC + "[0m";

    private static final boolean ENABLED = decide();

    private Ansi() { }

    private static boolean decide() {
        if (System.getenv("NO_COLOR") != null) return false;
        if (System.getenv("FORCE_COLOR") != null) return true;
        // TERM=dumb is what emacs shell-mode and some CI runners set, and they
        // mean it: they render escape sequences literally.
        if ("dumb".equals(System.getenv("TERM"))) return false;
        return System.console() != null;
    }

    public static boolean enabled() {
        return ENABLED;
    }

    private static String wrap(String code, String text) {
        return ENABLED ? ESC + code + text + RESET : text;
    }

    public static String bold(String s)      { return wrap("[1m", s); }
    public static String dim(String s)       { return wrap("[2m", s); }
    public static String red(String s)       { return wrap("[31m", s); }
    public static String green(String s)     { return wrap("[32m", s); }
    public static String yellow(String s)    { return wrap("[33m", s); }
    public static String blue(String s)      { return wrap("[34m", s); }
    public static String magenta(String s)   { return wrap("[35m", s); }
    public static String cyan(String s)      { return wrap("[36m", s); }
    public static String brightRed(String s) { return wrap("[91m", s); }

    /** Bold white on the brand red — used for one header rule and nothing else. */
    public static String banner(String s) {
        return wrap("[1;97;41m", s);
    }

    /** Clear the screen and home the cursor, for the live view. */
    public static String clearScreen() {
        return ENABLED ? ESC + "[2J" + ESC + "[H" : System.lineSeparator();
    }

    public static String hideCursor() { return ENABLED ? ESC + "[?25l" : ""; }

    public static String showCursor() { return ENABLED ? ESC + "[?25h" : ""; }

    /**
     * Visible width, ignoring escape sequences.
     *
     * <p>Needed because every column in every table is padded to a width, and
     * {@link String#length()} counts the invisible characters in a coloured
     * cell — which is why hand-rolled ANSI tables come out ragged in exactly
     * the coloured columns.
     */
    public static int width(String s) {
        int n = 0;
        boolean inEscape = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inEscape) {
                if (c == 'm') inEscape = false;
            } else if (c == '\u001B') {
                inEscape = true;
            } else {
                n++;
            }
        }
        return n;
    }

    /** Pad to a visible width, so coloured and plain cells line up. */
    public static String pad(String s, int to) {
        int gap = to - width(s);
        return gap <= 0 ? s : s + " ".repeat(gap);
    }

    public static String padLeft(String s, int to) {
        int gap = to - width(s);
        return gap <= 0 ? s : " ".repeat(gap) + s;
    }
}
