package com.bank.testkit;

/** Minimal dependency-free assertions, matching the style of the existing smoke test. */
public final class TestKit {
    private static int passed, failed;

    private TestKit() { }

    public static void section(String title) { System.out.println("\n" + title); }

    public static void check(String name, boolean ok) { check(name, ok, ""); }

    public static void check(String name, boolean ok, String detail) {
        if (ok) { passed++; System.out.println("  ✓ " + name); }
        else { failed++; System.out.println("  ✗ " + name + (detail.isEmpty() ? "" : "   -> " + detail)); }
    }

    public static void checkEquals(String name, Object expected, Object actual) {
        check(name, java.util.Objects.equals(expected, actual), "expected=" + expected + " actual=" + actual);
    }

    /** Asserts that {@code body} throws {@code type}. */
    public static void checkThrows(String name, Class<? extends Throwable> type, Runnable body) {
        try {
            body.run();
            check(name, false, "no exception thrown");
        } catch (Throwable t) {
            check(name, type.isInstance(t), "threw " + t);
        }
    }

    /** Prints the summary and exits non-zero on any failure. */
    public static void finish(String suite) {
        System.out.println("\n" + suite + ": " + passed + " passed, " + failed + " failed");
        if (failed > 0) System.exit(1);
    }
}
