package corretto.update;

import java.io.StringReader;
import java.util.List;
import java.util.Map;

public final class JsonParserTest {
    public static void main(String[] args) throws Exception {
        Object parsed = JsonParser.parse(new StringReader(
            "{\"a\": [1, 2], \"b\": {\"c\": \"d\"}, \"e\": true}"));
        Map<?, ?> root = (Map<?, ?>) parsed;
        Check.eq(2, ((List<?>) root.get("a")).size());
        Check.eq("d", ((Map<?, ?>) root.get("b")).get("c"));
        Check.eq(Boolean.TRUE, root.get("e"));
        // Incremental behavior: parse() must stop at the end of the first value,
        // leaving trailing garbage unread (this is what Task 8 relies on).
        Object first = JsonParser.parse(new StringReader("{\"x\": 1} TRAILING GARBAGE"));
        Check.eq(1, ((Number) ((Map<?, ?>) first).get("x")).intValue());
        System.out.println("JsonParserTest OK");
    }
}
