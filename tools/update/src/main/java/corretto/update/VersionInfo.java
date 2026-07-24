package corretto.update;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public record VersionInfo(List<Integer> lts, List<Integer> feature) {

    public static VersionInfo parse(Reader in) {
        JsonObject root = JsonParser.parseReader(in).getAsJsonObject();
        return new VersionInfo(
            ints(root.getAsJsonArray("supported_lts_releases")),
            ints(root.getAsJsonArray("supported_feature_releases")));
    }

    public List<Integer> majors(boolean includeFeature) {
        List<Integer> result = new ArrayList<>(lts);
        if (includeFeature) {
            result.addAll(feature);
        }
        return result.stream().sorted().toList();
    }

    public static String lifecycleReport(Set<Integer> current, Set<Integer> desired) {
        StringBuilder sb = new StringBuilder();
        for (int added : new TreeSet<>(desired)) {
            if (!current.contains(added)) {
                sb.append("JDK line added: ").append(added).append('\n');
            }
        }
        for (int removed : new TreeSet<>(current)) {
            if (!desired.contains(removed)) {
                sb.append("JDK line removed (EOL): ").append(removed).append('\n');
            }
        }
        return sb.toString();
    }

    private static List<Integer> ints(JsonArray array) {
        List<Integer> result = new ArrayList<>();
        for (JsonElement e : array) {
            result.add(e.getAsInt());
        }
        return result;
    }
}
