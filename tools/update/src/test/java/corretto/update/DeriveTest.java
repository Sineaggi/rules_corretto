package corretto.update;

import java.util.List;

public final class DeriveTest {

    private static Artifact art(int major, String os, String arch, String fullVersion, String file) {
        return new Artifact(major, os, arch, fullVersion,
            "/downloads/resources/" + fullVersion + "/" + file, "sha");
    }

    public static void main(String[] args) {
        Artifact linux = art(21, "linux", "x64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-linux-x64.tar.gz");
        Artifact alpine = art(21, "alpine", "aarch64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-alpine-linux-aarch64.tar.gz");
        Artifact macos = art(21, "macos", "aarch64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-macosx-aarch64.tar.gz");
        Artifact win = art(21, "windows", "x64", "21.0.12.8.1",
            "amazon-corretto-21.0.12.8.1-windows-x64-jdk.zip");
        Artifact win8 = art(8, "windows", "x64", "8.502.07.1",
            "amazon-corretto-8.502.07.1-windows-x64-jdk.zip");
        Artifact win11 = art(11, "windows", "x64", "11.0.32.9.1",
            "amazon-corretto-11.0.32.9.1-windows-x64-jdk.zip");

        Check.eq("https://corretto.aws/downloads/resources/21.0.12.8.1/amazon-corretto-21.0.12.8.1-linux-x64.tar.gz",
            Derive.url(linux));

        Check.eq("amazon-corretto-21.0.12.8.1-linux-x64", Derive.stripPrefix(linux));
        Check.eq("amazon-corretto-21.0.12.8.1-alpine-linux-aarch64", Derive.stripPrefix(alpine));
        Check.eq("amazon-corretto-21.jdk/Contents/Home", Derive.stripPrefix(macos));
        Check.eq("jdk21.0.12_8", Derive.stripPrefix(win));      // 21.0.12.8.1 -> jdk21.0.12_8
        Check.eq("jdk11.0.32_9", Derive.stripPrefix(win11));    // 11.0.32.9.1 -> jdk11.0.32_9
        Check.eq("jdk1.8.0_502", Derive.stripPrefix(win8));     // 8.502.07.1  -> jdk1.8.0_502

        Check.eq("corretto21_linux", Derive.repoName(linux));
        Check.eq("corretto_alpine21_linux_aarch64", Derive.repoName(alpine));
        Check.eq("corretto21_macos_aarch64", Derive.repoName(macos));
        Check.eq("corretto21_win", Derive.repoName(win));

        Check.eq("corretto", Derive.prefix(linux));
        Check.eq("corretto_alpine", Derive.prefix(alpine));

        Check.eq(List.of("@platforms//os:linux", "@platforms//cpu:x86_64"),
            Derive.targetCompatibleWith(linux));
        Check.eq(List.of("@platforms//os:linux", "@platforms//cpu:aarch64"),
            Derive.targetCompatibleWith(alpine));
        Check.eq(List.of("@platforms//os:macos", "@platforms//cpu:aarch64"),
            Derive.targetCompatibleWith(macos));
        Check.eq(List.of("@platforms//os:windows", "@platforms//cpu:x86_64"),
            Derive.targetCompatibleWith(win));
        System.out.println("DeriveTest OK");
    }
}
