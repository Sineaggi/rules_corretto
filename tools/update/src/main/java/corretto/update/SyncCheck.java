package corretto.update;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class SyncCheck {
    private SyncCheck() {}

    public static void check(String versionsBzl, String moduleBazel) {
        List<String> configNames = extract(versionsBzl, "name = \"([a-z0-9_]+)\"");

        int begin = moduleBazel.indexOf(StarlarkWriter.BEGIN_MARKER);
        int end = moduleBazel.indexOf(StarlarkWriter.END_MARKER);
        if (begin < 0 || end < 0) {
            throw new IllegalStateException("MODULE.bazel markers missing");
        }
        String block = moduleBazel.substring(begin, end);
        List<String> useRepoNames =
            extract(block, "    \"([a-z0-9_]+)_toolchain_config_repo\",");
        List<String> registered =
            extract(block, "register_toolchains\\(\"@([a-z0-9_]+)_toolchain_config_repo//:all\"\\)");

        // use_repo is expected in buildifier's order: lexicographic over the
        // full repo string including the _toolchain_config_repo suffix, which
        // the generator emits (StarlarkWriter.moduleBlock).
        List<String> sortedConfigNames = configNames.stream()
            .sorted(java.util.Comparator.comparing(n -> n + "_toolchain_config_repo"))
            .toList();
        if (!sortedConfigNames.equals(useRepoNames)) {
            throw new IllegalStateException(
                "versions.bzl and MODULE.bazel use_repo diverge.\nversions.bzl (sorted): "
                    + sortedConfigNames + "\nMODULE.bazel: " + useRepoNames
                    + "\nRun: bazel run //tools/update -- --write");
        }
        if (!configNames.equals(registered)) {
            throw new IllegalStateException(
                "versions.bzl and MODULE.bazel register_toolchains diverge.\nversions.bzl: "
                    + configNames + "\nregistered: " + registered
                    + "\nRun: bazel run //tools/update -- --write");
        }
    }

    /** Entry point for the repo-level sync_test (reads real files from runfiles). */
    public static void main(String[] args) throws Exception {
        String srcdir = System.getenv("TEST_SRCDIR");
        check(
            Files.readString(Path.of(srcdir, "_main", "corretto", "versions.bzl")),
            Files.readString(Path.of(srcdir, "_main", "MODULE.bazel")));
        System.out.println("SyncCheck OK");
    }

    private static List<String> extract(String content, String regex) {
        List<String> result = new ArrayList<>();
        Matcher m = Pattern.compile(regex).matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
