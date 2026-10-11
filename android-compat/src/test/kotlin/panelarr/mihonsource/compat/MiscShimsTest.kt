// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.app.Activity
import android.content.Intent
import android.icu.text.BreakIterator
import android.icu.text.Collator
import android.icu.text.Normalizer2
import android.icu.text.RuleBasedCollator
import android.icu.text.StringSearch
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.TextWatcher
import android.text.util.Linkify
import android.util.JsonReader
import android.util.JsonToken
import android.util.LruCache
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import org.junit.jupiter.api.Test
import java.io.StringReader
import java.text.StringCharacterIterator
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MiscShimsTest {
    @Test
    fun `json reader walks a document`() {
        val reader = JsonReader(StringReader("""{"a": [1, 2.5, "x"], "b": null, "c": {"d": true}, "e": "skip"}"""))
        reader.beginObject()
        assertEquals("a", reader.nextName())
        reader.beginArray()
        assertEquals(1, reader.nextInt())
        assertEquals(2.5, reader.nextDouble())
        assertEquals("x", reader.nextString())
        assertFalse(reader.hasNext())
        reader.endArray()
        assertEquals("b", reader.nextName())
        assertEquals(JsonToken.NULL, reader.peek())
        reader.nextNull()
        assertEquals("c", reader.nextName())
        reader.skipValue()
        assertEquals("e", reader.nextName())
        assertEquals("skip", reader.nextString())
        reader.endObject()
        assertEquals(JsonToken.END_DOCUMENT, reader.peek())
    }

    @Test
    fun `lru cache evicts the least recently used`() {
        val cache = LruCache<String, Int>(2)
        cache.put("a", 1)
        cache.put("b", 2)
        cache.get("a")
        cache.put("c", 3)
        assertNull(cache.get("b"))
        assertEquals(1, cache.get("a"))
        assertEquals(2, cache.size())
    }

    @Test
    fun `intents and bundles hold data`() {
        val intent =
            Intent()
                .setAction(Intent.ACTION_VIEW)
                .putExtra("query", "x")
                .putExtra("id", 5L)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.setData(Uri.parse("https://example.org"))
        assertEquals("x", intent.getStringExtra("query"))
        assertEquals(5L, intent.getLongExtra("id", 0))
        assertEquals("https://example.org", intent.data.toString())

        val bundle = Bundle().apply { putStringArrayList("list", arrayListOf("a")) }
        assertEquals(listOf("a"), bundle.getStringArrayList("list"))
        assertEquals(0, bundle.getInt("missing"))

        val activity = Activity()
        assertEquals("app.mihonsource", activity.packageName)
        assertTrue(Build.VERSION.SDK_INT >= 34)
    }

    @Test
    fun `edit text notifies watchers and views find each other`() {
        val root = FrameLayout(AndroidHost.application)
        val edit = EditText(AndroidHost.application)
        val ok = Button(AndroidHost.application).apply { id = android.R.id.button1 }
        root.addView(edit)
        root.addView(ok)
        edit.addTextChangedListener(
            object : TextWatcher {
                override fun beforeTextChanged(
                    s: CharSequence,
                    start: Int,
                    count: Int,
                    after: Int,
                ) = Unit

                override fun onTextChanged(
                    s: CharSequence,
                    start: Int,
                    before: Int,
                    count: Int,
                ) = Unit

                override fun afterTextChanged(s: android.text.Editable) {
                    edit.rootView.findViewById<Button>(android.R.id.button1).isEnabled = s.isNotEmpty()
                }
            },
        )
        edit.setText("")
        assertFalse(ok.isEnabled)
        edit.setText("value")
        assertTrue(ok.isEnabled)
        assertEquals("value", edit.text.toString())
        assertSame(root, edit.rootView)
    }

    @Test
    fun `icu helpers work`() {
        val words = BreakIterator.getWordInstance()
        words.setText(StringCharacterIterator("hi 42"))
        words.first()
        assertEquals(2, words.next())
        assertEquals(BreakIterator.WORD_LETTER, words.ruleStatus)

        val collator = Collator.getInstance() as RuleBasedCollator
        collator.strength = Collator.PRIMARY
        val search = StringSearch("cafe", StringCharacterIterator("Le Café est"), collator)
        assertEquals(3, search.first())
        assertEquals("Café", search.matchedText)

        assertEquals("abc", Normalizer2.getNFKCCasefoldInstance().normalize("ＡＢＣ"))

        val spannable = SpannableString("mail a@b.co or see https://example.org")
        assertTrue(Linkify.addLinks(spannable, Linkify.ALL))
    }
}
