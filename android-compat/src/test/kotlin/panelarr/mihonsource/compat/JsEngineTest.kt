// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import app.cash.quickjs.QuickJs
import app.cash.quickjs.QuickJsException
import com.squareup.duktape.Duktape
import com.squareup.duktape.DuktapeException
import org.junit.jupiter.api.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class JsEngineTest {
    interface Calculator {
        fun add(
            a: Int,
            b: Int,
        ): Int

        fun greet(name: String): String
    }

    @Test
    fun `quickjs maps results like QuickJS`() {
        QuickJs.create().use { js ->
            assertEquals(3, js.evaluate("1 + 2"))
            assertEquals(2.5, js.evaluate("5 / 2"))
            assertEquals(4_294_967_296.0, js.evaluate("2 ** 32"))
            assertEquals("ab", js.evaluate("'a' + 'b'", "test.js"))
            assertEquals(true, js.evaluate("1 < 2"))
            assertNull(js.evaluate("undefined"))
            assertNull(js.evaluate("null"))
            val array = js.evaluate("[1, 'x', [2.5, null]]") as Array<*>
            assertEquals(listOf<Any?>(1, "x"), array.take(2))
            assertContentEquals(arrayOf<Any?>(2.5, null), array[2] as Array<Any?>)

            // State persists between calls on one instance.
            js.evaluate("var counter = 41;")
            assertEquals(42, js.evaluate("++counter"))

            val error = assertFailsWith<QuickJsException> { js.evaluate("missingFunction()") }
            assertTrue(error.message!!.contains("missingFunction"))
            assertFailsWith<QuickJsException> { js.evaluate("throw new Error('boom')") }

            val bytes = js.compile("counter * 2", "c.js")
            assertEquals(84, js.execute(bytes))
            assertFailsWith<QuickJsException> { js.compile("function (", "bad.js") }
        }
    }

    @Test
    fun `quickjs binds interfaces both ways`() {
        QuickJs.create().use { js ->
            js.set(
                "calc",
                Calculator::class.java,
                object : Calculator {
                    override fun add(
                        a: Int,
                        b: Int,
                    ) = a + b

                    override fun greet(name: String) = "hi $name"
                },
            )
            assertEquals(5, js.evaluate("calc.add(2, 3)"))
            assertEquals("hi js", js.evaluate("calc.greet('js')"))

            js.evaluate("var impl = { add: function (a, b) { return a * b; }, greet: function (n) { return n + '!'; } };")
            val impl = js.get("impl", Calculator::class.java)
            assertEquals(12, impl.add(3, 4))
            assertEquals("x!", impl.greet("x"))
        }
    }

    @Test
    fun `duktape returns doubles`() {
        Duktape.create().use { js ->
            assertEquals(3.0, js.evaluate("1 + 2"))
            assertEquals("s", js.evaluate("'s'"))
            assertFailsWith<DuktapeException> { js.evaluate("nope.x") }
        }
    }
}
