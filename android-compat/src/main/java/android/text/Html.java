// SPDX-License-Identifier: GPL-3.0-or-later

package android.text;

import java.util.Set;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;

/**
 * Android's Html helper. {@link #fromHtml} parses with jsoup and returns the readable text:
 * whitespace collapsed, line breaks for {@code <br>} and block elements, entities decoded.
 * Styling spans are not produced; extensions only read the text.
 */
public class Html {
    public static final int FROM_HTML_MODE_LEGACY = 0;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH = 1;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_HEADING = 1 << 1;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST_ITEM = 1 << 2;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_LIST = 1 << 3;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_DIV = 1 << 4;
    public static final int FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE = 1 << 5;
    public static final int FROM_HTML_OPTION_USE_CSS_COLORS = 1 << 8;
    public static final int FROM_HTML_MODE_COMPACT = 0x3F;
    public static final int TO_HTML_PARAGRAPH_LINES_CONSECUTIVE = 0;
    public static final int TO_HTML_PARAGRAPH_LINES_INDIVIDUAL = 1;

    private static final Set<String> SKIPPED = Set.of("script", "style", "head", "title", "template");

    public interface ImageGetter {
        Object getDrawable(String source);
    }

    public interface TagHandler {
        void handleTag(boolean opening, String tag, Editable output, org.xml.sax.XMLReader xmlReader);
    }

    private Html() {}

    @Deprecated
    public static Spanned fromHtml(String source) {
        return fromHtml(source, FROM_HTML_MODE_LEGACY);
    }

    public static Spanned fromHtml(String source, int flags) {
        return fromHtml(source, flags, null, null);
    }

    @Deprecated
    public static Spanned fromHtml(String source, ImageGetter imageGetter, TagHandler tagHandler) {
        return fromHtml(source, FROM_HTML_MODE_LEGACY, imageGetter, tagHandler);
    }

    public static Spanned fromHtml(String source, int flags, ImageGetter imageGetter, TagHandler tagHandler) {
        Element body = Jsoup.parseBodyFragment(source == null ? "" : source).body();
        TextBuilder out = new TextBuilder(flags);
        out.walk(body);
        return new SpannedString(out.sb);
    }

    public static String toHtml(Spanned text) {
        return toHtml(text, TO_HTML_PARAGRAPH_LINES_CONSECUTIVE);
    }

    /** Escapes the text and turns line breaks into paragraphs and {@code <br>}s. */
    public static String toHtml(Spanned text, int option) {
        StringBuilder out = new StringBuilder();
        String[] paragraphs = text.toString().split("\n\n+", -1);
        for (String paragraph : paragraphs) {
            if (paragraph.isEmpty()) continue;
            if (option == TO_HTML_PARAGRAPH_LINES_INDIVIDUAL) {
                for (String line : paragraph.split("\n")) out.append("<p dir=\"ltr\">").append(escapeHtml(line)).append("</p>\n");
            } else {
                out.append("<p dir=\"ltr\">").append(escapeHtml(paragraph).replace("\n", "<br>\n")).append("</p>\n");
            }
        }
        return out.toString();
    }

    /** Escapes {@code < > & "'}, runs of spaces and non-ASCII characters, as Android does. */
    public static String escapeHtml(CharSequence text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '&' -> out.append("&amp;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                case ' ' -> {
                    while (i + 1 < text.length() && text.charAt(i + 1) == ' ') {
                        out.append("&nbsp;");
                        i++;
                    }
                    out.append(' ');
                }
                default -> {
                    if (Character.isHighSurrogate(c) && i + 1 < text.length() && Character.isLowSurrogate(text.charAt(i + 1))) {
                        out.append("&#").append(Character.toCodePoint(c, text.charAt(++i))).append(';');
                    } else if (c > 0x7E || c < ' ') {
                        out.append("&#").append((int) c).append(';');
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Builds the text, keeping track of how many line breaks end it. */
    private static final class TextBuilder {
        private final StringBuilder sb = new StringBuilder();
        private final int flags;

        TextBuilder(int flags) {
            this.flags = flags;
        }

        /** Blank lines after a block: two in legacy mode, one when its compact flag is set. */
        private int breaks(int compactFlag) {
            return (flags & compactFlag) != 0 ? 1 : 2;
        }

        private int separator(String tag) {
            return switch (tag) {
                case "p" -> breaks(FROM_HTML_SEPARATOR_LINE_BREAK_PARAGRAPH);
                case "h1", "h2", "h3", "h4", "h5", "h6" -> breaks(FROM_HTML_SEPARATOR_LINE_BREAK_HEADING);
                case "ul", "ol", "dl" -> breaks(FROM_HTML_SEPARATOR_LINE_BREAK_LIST);
                case "li", "dt", "dd" -> 1;
                case "blockquote" -> breaks(FROM_HTML_SEPARATOR_LINE_BREAK_BLOCKQUOTE);
                case "div", "section", "article", "header", "footer", "aside", "nav", "main", "figure", "table", "tr", "pre",
                     "address", "hr", "form", "fieldset" -> 1;
                default -> 0;
            };
        }

        private void ensureBreaks(int count) {
            if (sb.length() == 0) return;
            int trailing = 0;
            while (trailing < sb.length() && sb.charAt(sb.length() - 1 - trailing) == '\n') trailing++;
            for (int i = trailing; i < count; i++) sb.append('\n');
        }

        void walk(Node node) {
            for (Node child : node.childNodes()) {
                if (child instanceof TextNode text) {
                    appendText(text.getWholeText(), child.parent() instanceof Element e && "pre".equals(e.normalName()));
                } else if (child instanceof Element element) {
                    element(element);
                }
            }
        }

        private void element(Element element) {
            String tag = element.normalName();
            if (SKIPPED.contains(tag)) return;
            switch (tag) {
                case "br" -> {
                    sb.append('\n');
                    return;
                }
                case "img" -> {
                    sb.append('￼');
                    return;
                }
                default -> {
                    // handled below
                }
            }
            int separator = separator(tag);
            if (separator > 0) ensureBreaks(separator);
            walk(element);
            if (separator > 0) ensureBreaks(separator);
        }

        private void appendText(String text, boolean preformatted) {
            if (preformatted) {
                sb.append(text);
                return;
            }
            for (int i = 0; i < text.length(); i++) {
                char c = text.charAt(i);
                if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\f') {
                    // Collapse whitespace; drop it at the start of a line.
                    if (sb.length() > 0) {
                        char last = sb.charAt(sb.length() - 1);
                        if (last != ' ' && last != '\n') sb.append(' ');
                    }
                } else {
                    sb.append(c);
                }
            }
        }
    }
}
