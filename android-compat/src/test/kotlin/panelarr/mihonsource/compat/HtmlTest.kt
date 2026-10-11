// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.text.Html
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class HtmlTest {
    @Test
    fun `fromHtml returns the readable text`() {
        assertEquals("Tom & Jerry <3", Html.fromHtml("Tom &amp; Jerry &lt;3", Html.FROM_HTML_MODE_LEGACY).toString())
        assertEquals("a b c", Html.fromHtml("  a \n  b\t c", 0).toString())
        assertEquals("one\ntwo", Html.fromHtml("one<br>two", 0).toString())
        assertEquals("first\n\nsecond\n\n", Html.fromHtml("<p>first</p><p>second</p>", Html.FROM_HTML_MODE_LEGACY).toString())
        assertEquals("first\nsecond\n", Html.fromHtml("<p>first</p><p>second</p>", Html.FROM_HTML_MODE_COMPACT).toString())
        assertEquals("bold and italic", Html.fromHtml("<b>bold</b> and <i>italic</i><script>x()</script>").toString())
        assertEquals("x\ny", Html.fromHtml("<div>x</div><div>y</div>", 0).toString().trim())
    }

    @Test
    fun `escapeHtml and toHtml`() {
        assertEquals("a&lt;b&gt; &amp; &quot;c&quot; &#233;", Html.escapeHtml("a<b> & \"c\" é"))
        assertEquals("<p dir=\"ltr\">x&amp;y</p>\n", Html.toHtml(Html.fromHtml("x&amp;y", 0)))
    }
}
