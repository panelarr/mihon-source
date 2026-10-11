// SPDX-License-Identifier: GPL-3.0-or-later

package android.text.style;

/** A link span. */
public class URLSpan {
    private final String url;

    public URLSpan(String url) {
        this.url = url;
    }

    public String getURL() {
        return url;
    }
}
