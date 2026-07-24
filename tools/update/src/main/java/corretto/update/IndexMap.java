package corretto.update;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class IndexMap {
    private IndexMap() {}

    // Expected matrix: os -> archs. Windows aarch64 does not exist upstream.
    private static final List<String> OS_ORDER = List.of("linux", "alpine", "macos", "windows");
    private static final List<String> ARCH_ORDER = List.of("x64", "aarch64");

    private static List<String> archesFor(String os) {
        return os.equals("windows") ? List.of("x64") : ARCH_ORDER;
    }

    public static List<Artifact> parse(Reader in, List<Integer> majors) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        List<Artifact> result = new ArrayList<>();
        for (String os : OS_ORDER) {
            for (String arch : archesFor(os)) {
                for (int major : majors) {
                    result.add(lookup(root, os, arch, major));
                }
            }
        }
        result.sort(Comparator
            .comparingInt(Artifact::major)
            .thenComparingInt(a -> OS_ORDER.indexOf(a.os()))
            .thenComparingInt(a -> ARCH_ORDER.indexOf(a.arch())));
        return result;
    }

    private static JsonObject childObject(JsonElement node, String key) {
        if (node == null || !node.isJsonObject()) {
            return null;
        }
        JsonElement child = node.getAsJsonObject().get(key);
        return (child != null && child.isJsonObject()) ? child.getAsJsonObject() : null;
    }

    private static Artifact lookup(JsonObject root, String os, String arch, int major) {
        String format = os.equals("windows") ? "zip" : "tar.gz";
        JsonObject node = childObject(root, os);
        node = childObject(node, arch);
        node = childObject(node, "jdk");
        node = childObject(node, String.valueOf(major));
        JsonObject entry = childObject(node, format);
        if (entry == null) {
            throw new IllegalStateException(
                "indexmap is missing expected combination: " + os + "/" + arch + "/jdk/"
                    + major + "/" + format);
        }
        JsonElement resource = entry.get("resource");
        JsonElement sha256 = entry.get("checksum_sha256");
        if (resource == null || sha256 == null) {
            throw new IllegalStateException(
                "indexmap entry incomplete for " + os + "/" + arch + "/" + major);
        }
        // resource = /downloads/resources/<fullVersion>/<file>
        String[] parts = resource.getAsString().split("/");
        String fullVersion = parts[3];
        return new Artifact(major, os, arch, fullVersion, resource.getAsString(), sha256.getAsString());
    }
}
