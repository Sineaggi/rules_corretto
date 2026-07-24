package corretto.update;

import java.util.List;

public final class Derive {
    private Derive() {}

    public static String url(Artifact a) {
        return "https://corretto.aws" + a.resource();
    }

    public static String stripPrefix(Artifact a) {
        switch (a.os()) {
            case "linux":
            case "alpine": {
                String file = a.fileName();
                return file.substring(0, file.length() - ".tar.gz".length());
            }
            case "macos":
                return "amazon-corretto-" + a.major() + ".jdk/Contents/Home";
            case "windows": {
                // fullVersion a.b.c.d[.e]; verified: 21.0.12.8.1 -> jdk21.0.12_8,
                // 8.502.07.1 -> jdk1.8.0_502 (leading zeros dropped via int parse).
                String[] p = a.fullVersion().split("\\.");
                if (a.major() == 8) {
                    return "jdk1.8.0_" + Integer.parseInt(p[1]);
                }
                return "jdk" + p[0] + "." + p[1] + "." + p[2] + "_" + Integer.parseInt(p[3]);
            }
            default:
                throw new IllegalArgumentException("unknown os: " + a.os());
        }
    }

    public static String repoName(Artifact a) {
        String base = a.os().equals("alpine") ? "corretto_alpine" : "corretto";
        String osPart = switch (a.os()) {
            case "linux", "alpine" -> "linux";
            case "macos" -> "macos";
            case "windows" -> "win";
            default -> throw new IllegalArgumentException("unknown os: " + a.os());
        };
        String archPart = a.arch().equals("x64") ? "" : "_" + a.arch();
        return base + a.major() + "_" + osPart + archPart;
    }

    public static String prefix(Artifact a) {
        return a.os().equals("alpine") ? "corretto_alpine" : "corretto";
    }

    public static List<String> targetCompatibleWith(Artifact a) {
        String os = switch (a.os()) {
            case "linux", "alpine" -> "@platforms//os:linux";
            case "macos" -> "@platforms//os:macos";
            case "windows" -> "@platforms//os:windows";
            default -> throw new IllegalArgumentException("unknown os: " + a.os());
        };
        String cpu = a.arch().equals("x64")
            ? "@platforms//cpu:x86_64"
            : "@platforms//cpu:aarch64";
        return List.of(os, cpu);
    }
}
