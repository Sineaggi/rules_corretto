package corretto.update;

import java.io.StringReader;
import java.util.List;

public final class IndexMapTest {

    private static String entry(String resource, String sha) {
        return "{\"checksum\": \"m\", \"checksum_sha256\": \"" + sha
            + "\", \"checksum_sha384\": \"x\", \"resource\": \"" + resource + "\"}";
    }

    // Minimal indexmap covering major 21 completely, plus noise that must be ignored:
    // an unknown os (al2023), an unknown arch (x86), a jre image, and a .sig format.
    private static String sample() {
        String v = "21.0.12.8.1";
        return "{"
            + "\"linux\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {"
            +     "\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-linux-x64.tar.gz", "sha-linux-x64") + ","
            +     "\"tar.gz.sig\": " + entry("/x.sig", "nope")
            +   "}},"
            +   "\"jre\": {\"21\": {\"tar.gz\": " + entry("/jre.tar.gz", "nope") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-linux-aarch64.tar.gz", "sha-linux-aarch64") + "}}},"
            +   "\"x86\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/x86.tar.gz", "nope") + "}}}"
            + "},"
            + "\"alpine\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-alpine-linux-x64.tar.gz", "sha-alpine-x64") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-alpine-linux-aarch64.tar.gz", "sha-alpine-aarch64") + "}}}"
            + "},"
            + "\"macos\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-macosx-x64.tar.gz", "sha-macos-x64") + "}}},"
            +   "\"aarch64\": {\"jdk\": {\"21\": {\"tar.gz\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-macosx-aarch64.tar.gz", "sha-macos-aarch64") + "}}}"
            + "},"
            + "\"windows\": {"
            +   "\"x64\": {\"jdk\": {\"21\": {\"zip\": " + entry("/downloads/resources/" + v + "/amazon-corretto-" + v + "-windows-x64-jdk.zip", "sha-win-x64") + "}}}"
            + "},"
            + "\"al2023\": {\"x64\": {\"jdk\": {\"21\": {\"rpm\": " + entry("/r.rpm", "nope") + "}}}}"
            + "}";
    }

    public static void main(String[] args) throws Exception {
        List<Artifact> artifacts = IndexMap.parse(new StringReader(sample()), List.of(21));
        Check.eq(7, artifacts.size());

        Artifact first = artifacts.get(0);
        Check.eq("linux", first.os());
        Check.eq("x64", first.arch());
        Check.eq(21, first.major());
        Check.eq("21.0.12.8.1", first.fullVersion());
        Check.eq("sha-linux-x64", first.sha256());

        // Deterministic order: linux x64, linux aarch64, alpine x64, alpine aarch64,
        // macos x64, macos aarch64, windows x64.
        Check.eq(
            List.of("linux/x64", "linux/aarch64", "alpine/x64", "alpine/aarch64",
                "macos/x64", "macos/aarch64", "windows/x64"),
            artifacts.stream().map(a -> a.os() + "/" + a.arch()).toList());

        // A missing expected combo is an error naming the combo.
        boolean threw = false;
        try {
            IndexMap.parse(new StringReader(sample()), List.of(21, 17));
        } catch (IllegalStateException e) {
            threw = true;
            Check.isTrue(e.getMessage().contains("17"), "message names the major: " + e.getMessage());
        }
        Check.isTrue(threw, "missing major 17 must throw");
        System.out.println("IndexMapTest OK");
    }
}
