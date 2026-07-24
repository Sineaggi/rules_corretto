package corretto.update;

public final class SyncCheckTest {

    private static String module(String... repos) {
        StringBuilder sb = new StringBuilder("module(name = \"x\")\n")
            .append(StarlarkWriter.BEGIN_MARKER).append('\n')
            .append("use_repo(\n    corretto,\n");
        for (String r : repos) {
            sb.append("    \"").append(r).append("_toolchain_config_repo\",\n");
        }
        sb.append(")\n");
        for (String r : repos) {
            sb.append("register_toolchains(\"@").append(r)
                .append("_toolchain_config_repo//:all\")\n");
        }
        return sb.append(StarlarkWriter.END_MARKER).append('\n').toString();
    }

    private static String versions(String... names) {
        StringBuilder sb = new StringBuilder("CORRETTO_JDK_CONFIGS = [\n");
        for (String n : names) {
            sb.append("    struct(\n        name = \"").append(n).append("\",\n    ),\n");
        }
        return sb.append("]\n").toString();
    }

    public static void main(String[] args) {
        // In sync: no exception.
        SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
            module("corretto21_linux", "corretto21_win"));

        // Out of sync: missing repo in MODULE.
        boolean threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module("corretto21_linux"));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("corretto21_win"), "names divergence: " + e.getMessage());
        }
        Check.isTrue(threw, "must throw on divergence");

        // Out of sync: order differs.
        threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module("corretto21_win", "corretto21_linux"));
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "must throw on order divergence");
        System.out.println("SyncCheckTest OK");
    }
}
