package corretto.update;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public record Updater(Fetcher fetcher, Path versionsBzl, Path moduleBazel, boolean includeFeature) {

    public static final String VERSION_INFO_URL =
        "https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/version-info.json";
    public static final String INDEXMAP_URL =
        "https://raw.githubusercontent.com/corretto/corretto-downloads/main/latest_links/indexmap_with_checksum.json";

    public record Result(
        String newVersionsBzl, String newModuleBazel, String report, boolean changed) {}

    public Result run() throws IOException {
        VersionInfo info;
        try (InputStream in = fetcher.open(VERSION_INFO_URL)) {
            info = VersionInfo.parse(new InputStreamReader(in, StandardCharsets.UTF_8));
        }
        List<Artifact> artifacts;
        try (InputStream in = fetcher.open(INDEXMAP_URL)) {
            artifacts = IndexMap.parse(
                new InputStreamReader(in, StandardCharsets.UTF_8), info.majors(includeFeature));
        }

        String currentVersions = Files.readString(versionsBzl);
        String currentModule = Files.readString(moduleBazel);

        // Streamed strip_prefix verification, changed entries only (spec §3).
        Set<String> currentShas = extract(currentVersions, "sha256 = \"([0-9a-zA-Z-]+)\"");
        for (Artifact a : artifacts) {
            if (!currentShas.contains(a.sha256())) {
                String expected = StreamCheck.firstSegment(Derive.stripPrefix(a));
                String actual;
                try (InputStream in = fetcher.open(Derive.url(a))) {
                    actual = StreamCheck.topLevelDir(in, a.format());
                }
                if (!expected.equals(actual)) {
                    throw new IllegalStateException(
                        "derived strip_prefix mismatch for " + Derive.repoName(a)
                            + ": derived first segment '" + expected
                            + "' but archive top-level dir is '" + actual + "'");
                }
            }
        }

        String newVersions = StarlarkWriter.versionsBzl(artifacts);
        String newModule = StarlarkWriter.replaceBlock(
            currentModule, StarlarkWriter.moduleBlock(artifacts));

        Set<Integer> currentMajors = new TreeSet<>();
        for (String v : extract(currentVersions, "version = \"(\\d+)\"")) {
            currentMajors.add(Integer.parseInt(v));
        }
        String lifecycle =
            VersionInfo.lifecycleReport(currentMajors, new TreeSet<>(info.majors(includeFeature)));

        boolean changed =
            !newVersions.equals(currentVersions) || !newModule.equals(currentModule);
        String report = (changed ? "configs changed\n" : "up to date\n") + lifecycle;
        return new Result(newVersions, newModule, report, changed);
    }

    /** Atomic write: temp file in the same directory, then move into place. */
    public static void writeAtomically(Path target, String content) throws IOException {
        Path tmp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.writeString(tmp, content);
        Files.move(tmp, target,
            java.nio.file.StandardCopyOption.REPLACE_EXISTING,
            java.nio.file.StandardCopyOption.ATOMIC_MOVE);
    }

    private static Set<String> extract(String content, String regex) {
        Set<String> result = new HashSet<>();
        Matcher m = Pattern.compile(regex).matcher(content);
        while (m.find()) {
            result.add(m.group(1));
        }
        return result;
    }
}
