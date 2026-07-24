package corretto.update;

import java.util.List;

public final class StarlarkWriterTest {

    private static final Artifact LINUX = new Artifact(21, "linux", "x64", "21.0.12.8.1",
        "/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz", "abc");
    private static final Artifact ALPINE = new Artifact(21, "alpine", "x64", "21.0.12.8.1",
        "/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-alpine-linux-x64.tar.gz", "def");

    public static void main(String[] args) {
        String bzl = StarlarkWriter.versionsBzl(List.of(LINUX, ALPINE));

        Check.isTrue(bzl.startsWith("# GENERATED FILE - do not edit by hand."),
            "generated header present");
        Check.isTrue(bzl.contains("CORRETTO_JDK_CONFIGS = ["), "list declared");
        Check.isTrue(bzl.contains("        name = \"corretto21_linux\",\n"), "entry name");
        Check.isTrue(bzl.contains("        prefix = \"corretto_alpine\",\n"), "alpine prefix");
        Check.isTrue(bzl.contains("        version = \"21\",\n"), "bare major only");
        Check.isTrue(!bzl.contains("version = \"21.0.12.8.1\""), "full version never in version field");
        Check.isTrue(bzl.contains(
            "        sha256 = \"abc\",\n"
            + "        strip_prefix = \"amazon-corretto-21.0.12.8.1-linux-x64\",\n"
            + "        urls = [\"https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz\"],\n"),
            "sha/strip/urls block");
        Check.isTrue(bzl.contains(
            "        target_compatible_with = [\"@platforms//os:linux\", \"@platforms//cpu:x86_64\"],\n"),
            "constraints");

        // Emission is deterministic.
        Check.eq(bzl, StarlarkWriter.versionsBzl(List.of(LINUX, ALPINE)));

        String block = StarlarkWriter.moduleBlock(List.of(LINUX, ALPINE));
        Check.isTrue(block.contains("    \"corretto21_linux_toolchain_config_repo\",\n"), "use_repo entry");
        Check.isTrue(block.contains(
            "register_toolchains(\"@corretto21_linux_toolchain_config_repo//:all\")"), "register line");
        Check.isTrue(block.contains(
            "register_toolchains(\"@corretto_alpine21_linux_toolchain_config_repo//:all\")"),
            "alpine register line");

        // use_repo args are emitted in the order buildifier enforces:
        // lexicographic over the FULL repo string including the
        // _toolchain_config_repo suffix. That puts corretto21 < corretto8
        // ('2' < '8') AND corretto21_linux_aarch64_... < corretto21_linux_...
        // ('a' of aarch64 < 't' of toolchain after the shared prefix).
        // register_toolchains keeps artifact (major/platform) order.
        Artifact major8 = new Artifact(8, "linux", "x64", "8.502.07.1",
            "/downloads/resources/8.502.07.1/amazon-corretto-8.502.07.1-linux-x64.tar.gz", "ggg");
        Artifact linuxArm = new Artifact(21, "linux", "aarch64", "21.0.12.8.1",
            "/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-aarch64.tar.gz", "hhh");
        String mixed = StarlarkWriter.moduleBlock(List.of(major8, LINUX, linuxArm));
        Check.isTrue(mixed.contains(
            "use_repo(\n    corretto,\n"
            + "    \"corretto21_linux_aarch64_toolchain_config_repo\",\n"
            + "    \"corretto21_linux_toolchain_config_repo\",\n"
            + "    \"corretto8_linux_toolchain_config_repo\",\n"
            + ")\n"),
            "use_repo sorted like buildifier (full string incl. suffix): " + mixed);
        Check.isTrue(
            mixed.indexOf("register_toolchains(\"@corretto8_linux_toolchain_config_repo//:all\")")
                < mixed.indexOf("register_toolchains(\"@corretto21_linux_toolchain_config_repo//:all\")"),
            "register_toolchains keeps artifact order: " + mixed);

        String module = "module(name = \"rules_corretto\")\n\n"
            + "# BEGIN GENERATED REPOS - managed by //tools/update, do not edit\n"
            + "OLD CONTENT\n"
            + "# END GENERATED REPOS\n"
            + "\n# trailing\n";
        String rewritten = StarlarkWriter.replaceBlock(module, block);
        Check.isTrue(!rewritten.contains("OLD CONTENT"), "old block gone");
        Check.isTrue(rewritten.contains("corretto21_linux_toolchain_config_repo"), "new block in");
        Check.isTrue(rewritten.contains("# trailing"), "content after block preserved");
        Check.isTrue(rewritten.contains("module(name = \"rules_corretto\")"), "content before block preserved");
        // Idempotent: replacing again with the same block changes nothing.
        Check.eq(rewritten, StarlarkWriter.replaceBlock(rewritten, block));

        boolean threw = false;
        try {
            StarlarkWriter.replaceBlock("no markers here", block);
        } catch (IllegalStateException e) {
            threw = true;
        }
        Check.isTrue(threw, "missing markers must throw");
        System.out.println("StarlarkWriterTest OK");
    }
}
