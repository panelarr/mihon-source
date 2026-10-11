// SPDX-License-Identifier: GPL-3.0-or-later

package android.util;

import java.io.Closeable;
import java.io.IOException;
import java.io.Reader;
import java.util.ArrayList;
import java.util.List;

/**
 * A pull parser for JSON (RFC 7159). The reader keeps a stack of open containers and looks one
 * token ahead. Lenient mode additionally accepts unquoted names and strings, single quotes,
 * comments, and ';' or '=' / '=>' as separators.
 */
public final class JsonReader implements Closeable {
    private enum Scope { EMPTY_ARRAY, NONEMPTY_ARRAY, EMPTY_OBJECT, DANGLING_NAME, NONEMPTY_OBJECT, EMPTY_DOCUMENT, NONEMPTY_DOCUMENT, CLOSED }

    private final Reader in;
    private boolean lenient;
    private final List<Scope> stack = new ArrayList<>();
    private int peeked = -2; // a char pushed back, or -2 for none

    private JsonToken token;
    private String value; // the text of a NAME, STRING, NUMBER, BOOLEAN or NULL token

    public JsonReader(Reader in) {
        if (in == null) throw new NullPointerException("in == null");
        this.in = in;
        stack.add(Scope.EMPTY_DOCUMENT);
    }

    public void setLenient(boolean lenient) {
        this.lenient = lenient;
    }

    public boolean isLenient() {
        return lenient;
    }

    private Scope top() {
        return stack.get(stack.size() - 1);
    }

    private void replaceTop(Scope scope) {
        stack.set(stack.size() - 1, scope);
    }

    // --- characters ----------------------------------------------------------------------------

    private int read() throws IOException {
        if (peeked != -2) {
            int c = peeked;
            peeked = -2;
            return c;
        }
        return in.read();
    }

    private void unread(int c) {
        peeked = c;
    }

    /** The next character that is not whitespace or a comment, or -1 at the end. */
    private int nextNonWhitespace() throws IOException {
        while (true) {
            int c = read();
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') continue;
            if (c == '/' && lenient) {
                int next = read();
                if (next == '/') {
                    skipLine();
                    continue;
                } else if (next == '*') {
                    skipBlockComment();
                    continue;
                }
                unread(next);
                return c;
            }
            if (c == '#' && lenient) {
                skipLine();
                continue;
            }
            return c;
        }
    }

    private void skipLine() throws IOException {
        int c;
        do {
            c = read();
        } while (c != -1 && c != '\n' && c != '\r');
    }

    private void skipBlockComment() throws IOException {
        int prev = 0;
        int c;
        while ((c = read()) != -1) {
            if (prev == '*' && c == '/') return;
            prev = c;
        }
        throw syntaxError("Unterminated comment");
    }

    private MalformedJsonException syntaxError(String message) {
        return new MalformedJsonException(message);
    }

    private void checkLenient() throws IOException {
        if (!lenient) throw syntaxError("Use JsonReader.setLenient(true) to accept malformed JSON");
    }

    // --- tokens --------------------------------------------------------------------------------

    public JsonToken peek() throws IOException {
        if (token != null) return token;
        switch (top()) {
            case EMPTY_DOCUMENT -> {
                replaceTop(Scope.NONEMPTY_DOCUMENT);
                JsonToken first = readValue();
                if (!lenient && first != JsonToken.BEGIN_ARRAY && first != JsonToken.BEGIN_OBJECT) {
                    throw syntaxError("Expected JSON document to start with '[' or '{'");
                }
                return first;
            }
            case EMPTY_ARRAY -> {
                return nextInArray(true);
            }
            case NONEMPTY_ARRAY -> {
                return nextInArray(false);
            }
            case EMPTY_OBJECT -> {
                return nextInObject(true);
            }
            case DANGLING_NAME -> {
                return objectValue();
            }
            case NONEMPTY_OBJECT -> {
                return nextInObject(false);
            }
            case NONEMPTY_DOCUMENT -> {
                int c = nextNonWhitespace();
                if (c == -1) return token = JsonToken.END_DOCUMENT;
                checkLenient();
                unread(c);
                return readValue();
            }
            default -> throw new IllegalStateException("JsonReader is closed");
        }
    }

    private JsonToken nextInArray(boolean first) throws IOException {
        if (first) {
            replaceTop(Scope.NONEMPTY_ARRAY);
        } else {
            int c = nextNonWhitespace();
            switch (c) {
                case ']' -> {
                    stack.remove(stack.size() - 1);
                    return token = JsonToken.END_ARRAY;
                }
                case ';' -> checkLenient();
                case ',' -> { }
                default -> throw syntaxError("Unterminated array");
            }
        }
        int c = nextNonWhitespace();
        if (c == ']' && first) {
            stack.remove(stack.size() - 1);
            return token = JsonToken.END_ARRAY;
        }
        if ((c == ',' || c == ';' || c == ']')) {
            // An omitted value: "[1,,2]" reads as null in lenient mode.
            checkLenient();
            unread(c);
            value = "null";
            return token = JsonToken.NULL;
        }
        unread(c);
        return readValue();
    }

    private JsonToken nextInObject(boolean first) throws IOException {
        int c;
        if (first) {
            c = nextNonWhitespace();
            if (c == '}') {
                stack.remove(stack.size() - 1);
                return token = JsonToken.END_OBJECT;
            }
        } else {
            c = nextNonWhitespace();
            switch (c) {
                case '}' -> {
                    stack.remove(stack.size() - 1);
                    return token = JsonToken.END_OBJECT;
                }
                case ';' -> checkLenient();
                case ',' -> { }
                default -> throw syntaxError("Unterminated object");
            }
            c = nextNonWhitespace();
        }
        replaceTop(Scope.DANGLING_NAME);
        if (c == '"') {
            value = readString('"');
        } else if (c == '\'') {
            checkLenient();
            value = readString('\'');
        } else {
            checkLenient();
            unread(c);
            value = readLiteral();
            if (value.isEmpty()) throw syntaxError("Expected name");
        }
        return token = JsonToken.NAME;
    }

    private JsonToken objectValue() throws IOException {
        int c = nextNonWhitespace();
        switch (c) {
            case ':' -> { }
            case '=' -> {
                checkLenient();
                int next = read();
                if (next != '>') unread(next);
            }
            default -> throw syntaxError("Expected ':'");
        }
        replaceTop(Scope.NONEMPTY_OBJECT);
        return readValue();
    }

    private JsonToken readValue() throws IOException {
        int c = nextNonWhitespace();
        switch (c) {
            case '{' -> {
                stack.add(Scope.EMPTY_OBJECT);
                return token = JsonToken.BEGIN_OBJECT;
            }
            case '[' -> {
                stack.add(Scope.EMPTY_ARRAY);
                return token = JsonToken.BEGIN_ARRAY;
            }
            case '"' -> {
                value = readString('"');
                return token = JsonToken.STRING;
            }
            case '\'' -> {
                checkLenient();
                value = readString('\'');
                return token = JsonToken.STRING;
            }
            case -1 -> throw new java.io.EOFException("End of input");
            default -> {
                unread(c);
                return literalToken(readLiteral());
            }
        }
    }

    private JsonToken literalToken(String literal) throws IOException {
        if (literal.isEmpty()) throw syntaxError("Expected literal value");
        value = literal;
        if (literal.equals("null")) return token = JsonToken.NULL;
        if (literal.equals("true") || literal.equals("false")) return token = JsonToken.BOOLEAN;
        if (isNumber(literal)) return token = JsonToken.NUMBER;
        checkLenient();
        if (literal.equalsIgnoreCase("null")) return token = JsonToken.NULL;
        if (literal.equalsIgnoreCase("true") || literal.equalsIgnoreCase("false")) return token = JsonToken.BOOLEAN;
        return token = JsonToken.STRING;
    }

    private static boolean isNumber(String s) {
        return s.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?");
    }

    private String readLiteral() throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c == -1 || "{}[]:, \t\n\r\f/\\;#=\"'".indexOf(c) >= 0) {
                if (c != -1) unread(c);
                return sb.toString();
            }
            sb.append((char) c);
        }
    }

    private String readString(char quote) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = read();
            if (c == -1) throw syntaxError("Unterminated string");
            if (c == quote) return sb.toString();
            if (c != '\\') {
                sb.append((char) c);
                continue;
            }
            int e = read();
            switch (e) {
                case 'u' -> {
                    int code = 0;
                    for (int i = 0; i < 4; i++) {
                        int d = Character.digit(read(), 16);
                        if (d < 0) throw syntaxError("Invalid escape sequence");
                        code = code * 16 + d;
                    }
                    sb.append((char) code);
                }
                case 't' -> sb.append('\t');
                case 'b' -> sb.append('\b');
                case 'n' -> sb.append('\n');
                case 'r' -> sb.append('\r');
                case 'f' -> sb.append('\f');
                case -1 -> throw syntaxError("Unterminated escape sequence");
                default -> sb.append((char) e);
            }
        }
    }

    private void expect(JsonToken expected) throws IOException {
        JsonToken actual = peek();
        if (actual != expected) throw new IllegalStateException("Expected " + expected + " but was " + actual);
        token = null;
    }

    // --- API -------------------------------------------------------------------------------------

    public void beginArray() throws IOException {
        expect(JsonToken.BEGIN_ARRAY);
    }

    public void endArray() throws IOException {
        expect(JsonToken.END_ARRAY);
    }

    public void beginObject() throws IOException {
        expect(JsonToken.BEGIN_OBJECT);
    }

    public void endObject() throws IOException {
        expect(JsonToken.END_OBJECT);
    }

    public boolean hasNext() throws IOException {
        JsonToken t = peek();
        return t != JsonToken.END_OBJECT && t != JsonToken.END_ARRAY && t != JsonToken.END_DOCUMENT;
    }

    public String nextName() throws IOException {
        expect(JsonToken.NAME);
        return value;
    }

    public String nextString() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw new IllegalStateException("Expected a string but was " + t);
        token = null;
        return value;
    }

    public boolean nextBoolean() throws IOException {
        expect(JsonToken.BOOLEAN);
        return value.equalsIgnoreCase("true");
    }

    public void nextNull() throws IOException {
        expect(JsonToken.NULL);
    }

    public double nextDouble() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw new IllegalStateException("Expected a double but was " + t);
        double result = Double.parseDouble(value);
        if (!lenient && (Double.isNaN(result) || Double.isInfinite(result))) throw new NumberFormatException("JSON forbids NaN and infinities: " + result);
        token = null;
        return result;
    }

    public long nextLong() throws IOException {
        JsonToken t = peek();
        if (t != JsonToken.STRING && t != JsonToken.NUMBER) throw new IllegalStateException("Expected a long but was " + t);
        long result;
        try {
            result = Long.parseLong(value);
        } catch (NumberFormatException e) {
            double d = Double.parseDouble(value);
            result = (long) d;
            if (result != d) throw new NumberFormatException(value);
        }
        token = null;
        return result;
    }

    public int nextInt() throws IOException {
        long result = nextLong();
        if ((int) result != result) throw new NumberFormatException(value);
        return (int) result;
    }

    /** Skips the next value, including everything nested in it. */
    public void skipValue() throws IOException {
        int depth = 0;
        do {
            JsonToken t = peek();
            token = null;
            if (t == JsonToken.BEGIN_ARRAY || t == JsonToken.BEGIN_OBJECT) depth++;
            else if (t == JsonToken.END_ARRAY || t == JsonToken.END_OBJECT) depth--;
            else if (t == JsonToken.END_DOCUMENT) throw new IllegalStateException("Expected a value but was END_DOCUMENT");
        } while (depth > 0);
    }

    @Override
    public void close() throws IOException {
        token = null;
        stack.clear();
        stack.add(Scope.CLOSED);
        in.close();
    }

    @Override
    public String toString() {
        return "JsonReader";
    }
}
