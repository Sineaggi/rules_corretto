package corretto.update;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import java.io.StringReader;

public final class JsonSmokeTest {
    public static void main(String[] args) {
        JsonObject root = JsonParser.parseReader(new StringReader(
            "{\"a\": [1, 2], \"b\": {\"c\": \"d\"}, \"e\": true}")).getAsJsonObject();
        Check.eq(2, root.getAsJsonArray("a").size());
        Check.eq("d", root.getAsJsonObject("b").get("c").getAsString());
        Check.isTrue(root.get("e").getAsBoolean(), "boolean parses");
        // Streaming contract the updater relies on: parsing reads ONE value and
        // does not demand EOF — trailing bytes after the value stay unread, so it
        // can parse from a live HTTP stream that is closed early. As of Gson
        // 2.11.0, JsonParser.parseReader(Reader) does NOT have this property: it
        // eagerly peeks past the value and throws JsonSyntaxException on trailing
        // non-whitespace content. JsonParser.parseReader(JsonReader) does have it
        // (it simply stops after the value), so callers that need the streaming
        // contract must construct the JsonReader themselves, as here.
        JsonReader trailing = new JsonReader(new StringReader("{\"x\": 1} TRAILING GARBAGE"));
        JsonObject first = JsonParser.parseReader(trailing).getAsJsonObject();
        Check.eq(1, first.get("x").getAsInt());
        System.out.println("JsonSmokeTest OK");
    }
}
