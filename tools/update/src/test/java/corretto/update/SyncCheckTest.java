package corretto.update;

import java.util.List;

public final class SyncCheckTest {

    private static String module(List<String> useRepo, List<String> register) {
        StringBuilder sb = new StringBuilder("module(name = \"x\")\n")
            .append(StarlarkWriter.BEGIN_MARKER).append('\n')
            .append("use_repo(\n    corretto,\n");
        for (String r : useRepo) {
            sb.append("    \"").append(r).append("_toolchain_config_repo\",\n");
        }
        sb.append(")\n");
        for (String r : register) {
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
        // In sync: use_repo is sorted the way buildifier sorts it — over the
        // FULL string including the _toolchain_config_repo suffix (corretto11 <
        // corretto8, and ..._linux_aarch64_toolchain... < ..._linux_toolchain...).
        // register_toolchains keeps config order.
        SyncCheck.check(versions("corretto8_linux", "corretto11_linux"),
            module(List.of("corretto11_linux", "corretto8_linux"),
                List.of("corretto8_linux", "corretto11_linux")));
        SyncCheck.check(versions("corretto21_linux", "corretto21_linux_aarch64"),
            module(List.of("corretto21_linux_aarch64", "corretto21_linux"),
                List.of("corretto21_linux", "corretto21_linux_aarch64")));

        // Out of sync: use_repo in config (unsorted) order is a buildifier
        // divergence and must throw.
        boolean threw = false;
        try {
            SyncCheck.check(versions("corretto8_linux", "corretto11_linux"),
                module(List.of("corretto8_linux", "corretto11_linux"),
                    List.of("corretto8_linux", "corretto11_linux")));
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "must throw on unsorted use_repo");

        // Out of sync: missing repo in MODULE.
        threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module(List.of("corretto21_linux"), List.of("corretto21_linux")));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("corretto21_win"), "names divergence: " + e.getMessage());
        }
        Check.isTrue(threw, "must throw on divergence");

        // Out of sync: register_toolchains order differs from config order.
        threw = false;
        try {
            SyncCheck.check(versions("corretto21_linux", "corretto21_win"),
                module(List.of("corretto21_linux", "corretto21_win"),
                    List.of("corretto21_win", "corretto21_linux")));
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "must throw on register order divergence");
        System.out.println("SyncCheckTest OK");
    }
}
