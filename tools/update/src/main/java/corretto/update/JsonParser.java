package corretto.update;

import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal, dependency-free JSON parser that reads until a complete value is consumed.
 * Incremental behavior: parse() stops at the end of the first JSON value,
 * leaving any trailing content unread.
 */
public final class JsonParser {
    private static class JsonException extends IOException {
        JsonException(String msg) {
            super(msg);
        }
    }

    public static class JsonParseException extends IOException {
        public JsonParseException(String msg) {
            super(msg);
        }
        public JsonParseException(String msg, Throwable cause) {
            super(msg, cause);
        }
    }

    private JsonParser() {}

    public static Object parse(Reader in) throws JsonParseException, IOException {
        Parser p = new Parser(in);
        return p.parseValue();
    }

    private static class Parser {
        private final Reader in;
        private int current;
        private boolean eof;

        Parser(Reader in) throws IOException {
            this.in = in;
            advance();
        }

        private void advance() throws IOException {
            int ch = in.read();
            if (ch < 0) {
                eof = true;
                current = -1;
            } else {
                current = ch;
            }
        }

        private void skipWhitespace() throws IOException {
            while (!eof && Character.isWhitespace(current)) {
                advance();
            }
        }

        Object parseValue() throws JsonParseException, IOException {
            skipWhitespace();
            if (eof) {
                throw new JsonParseException("Unexpected end of input");
            }

            Object result;
            switch (current) {
                case '{':
                    result = parseObject();
                    break;
                case '[':
                    result = parseArray();
                    break;
                case '"':
                    result = parseString();
                    break;
                case 't':
                case 'f':
                    result = parseBoolean();
                    break;
                case 'n':
                    parseNull();
                    result = null;
                    break;
                case '-':
                case '0':
                case '1':
                case '2':
                case '3':
                case '4':
                case '5':
                case '6':
                case '7':
                case '8':
                case '9':
                    result = parseNumber();
                    break;
                default:
                    throw new JsonParseException("Unexpected character: " + (char) current);
            }
            return result;
        }

        private Map<String, Object> parseObject() throws JsonParseException, IOException {
            Map<String, Object> map = new HashMap<>();
            advance(); // consume '{'

            skipWhitespace();
            if (current == '}') {
                advance();
                return map;
            }

            while (true) {
                skipWhitespace();
                if (current != '"') {
                    throw new JsonParseException("Expected '\"' in object");
                }
                String key = parseString();

                skipWhitespace();
                if (current != ':') {
                    throw new JsonParseException("Expected ':' in object");
                }
                advance();

                Object value = parseValue();
                map.put(key, value);

                skipWhitespace();
                if (current == '}') {
                    advance();
                    return map;
                }
                if (current != ',') {
                    throw new JsonParseException("Expected ',' or '}' in object");
                }
                advance();
            }
        }

        private List<Object> parseArray() throws JsonParseException, IOException {
            List<Object> list = new ArrayList<>();
            advance(); // consume '['

            skipWhitespace();
            if (current == ']') {
                advance();
                return list;
            }

            while (true) {
                Object value = parseValue();
                list.add(value);

                skipWhitespace();
                if (current == ']') {
                    advance();
                    return list;
                }
                if (current != ',') {
                    throw new JsonParseException("Expected ',' or ']' in array");
                }
                advance();
            }
        }

        private String parseString() throws JsonParseException, IOException {
            StringBuilder sb = new StringBuilder();
            advance(); // consume opening '"'

            while (current != '"') {
                if (eof) {
                    throw new JsonParseException("Unterminated string");
                }
                if (current == '\\') {
                    advance();
                    if (eof) {
                        throw new JsonParseException("Unterminated string escape");
                    }
                    switch (current) {
                        case '"':
                        case '\\':
                        case '/':
                            sb.append((char) current);
                            break;
                        case 'b':
                            sb.append('\b');
                            break;
                        case 'f':
                            sb.append('\f');
                            break;
                        case 'n':
                            sb.append('\n');
                            break;
                        case 'r':
                            sb.append('\r');
                            break;
                        case 't':
                            sb.append('\t');
                            break;
                        case 'u':
                            sb.append(parseUnicodeEscape());
                            continue; // skip the final advance()
                        default:
                            throw new JsonParseException("Invalid escape sequence: \\" + (char) current);
                    }
                } else {
                    sb.append((char) current);
                }
                advance();
            }
            advance(); // consume closing '"'
            return sb.toString();
        }

        private char parseUnicodeEscape() throws JsonParseException, IOException {
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 4; i++) {
                advance();
                if (eof || !isHexDigit(current)) {
                    throw new JsonParseException("Invalid unicode escape");
                }
                hex.append((char) current);
            }
            return (char) Integer.parseInt(hex.toString(), 16);
        }

        private static boolean isHexDigit(int ch) {
            return (ch >= '0' && ch <= '9') ||
                   (ch >= 'a' && ch <= 'f') ||
                   (ch >= 'A' && ch <= 'F');
        }

        private boolean parseBoolean() throws JsonParseException, IOException {
            if (current == 't') {
                if (matchKeyword("true")) {
                    return true;
                }
            } else if (current == 'f') {
                if (matchKeyword("false")) {
                    return false;
                }
            }
            throw new JsonParseException("Invalid boolean");
        }

        private boolean matchKeyword(String keyword) throws IOException {
            for (int i = 0; i < keyword.length(); i++) {
                if (current != keyword.charAt(i)) {
                    return false;
                }
                if (i < keyword.length() - 1) {
                    advance();
                }
            }
            advance();
            return true;
        }

        private void parseNull() throws JsonParseException, IOException {
            if (!matchKeyword("null")) {
                throw new JsonParseException("Invalid null");
            }
        }

        private Number parseNumber() throws JsonParseException, IOException {
            StringBuilder sb = new StringBuilder();

            if (current == '-') {
                sb.append('-');
                advance();
            }

            if (current == '0') {
                sb.append('0');
                advance();
            } else if (current >= '1' && current <= '9') {
                while (current >= '0' && current <= '9') {
                    sb.append((char) current);
                    advance();
                }
            } else {
                throw new JsonParseException("Invalid number");
            }

            if (current == '.') {
                sb.append('.');
                advance();
                if (!(current >= '0' && current <= '9')) {
                    throw new JsonParseException("Invalid number: missing fractional part");
                }
                while (current >= '0' && current <= '9') {
                    sb.append((char) current);
                    advance();
                }
            }

            if (current == 'e' || current == 'E') {
                sb.append('E');
                advance();
                if (current == '+' || current == '-') {
                    sb.append((char) current);
                    advance();
                }
                if (!(current >= '0' && current <= '9')) {
                    throw new JsonParseException("Invalid number: missing exponent");
                }
                while (current >= '0' && current <= '9') {
                    sb.append((char) current);
                    advance();
                }
            }

            String numStr = sb.toString();
            try {
                if (numStr.contains(".") || numStr.contains("E")) {
                    return Double.parseDouble(numStr);
                } else {
                    long value = Long.parseLong(numStr);
                    if (value >= Integer.MIN_VALUE && value <= Integer.MAX_VALUE) {
                        return (int) value;
                    }
                    return value;
                }
            } catch (NumberFormatException e) {
                throw new JsonParseException("Invalid number: " + numStr, e);
            }
        }
    }
}
