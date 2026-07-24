package corretto.update;

import java.util.Objects;

public final class Check {
    private Check() {}

    public static void eq(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) {
            throw new AssertionError("expected: " + expected + "\nactual:   " + actual);
        }
    }

    public static void isTrue(boolean cond, String msg) {
        if (!cond) {
            throw new AssertionError(msg);
        }
    }
}
