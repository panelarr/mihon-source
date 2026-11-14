// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.jupiter.api.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.DelicateCoroutinesApi::class)
class MainThreadTest {
    @Test
    fun `handlers post to the main looper thread`() {
        val main = Looper.getMainLooper()
        assertEquals("main", main.thread.name)
        assertNull(Looper.myLooper())

        val latch = CountDownLatch(2)
        val seen = mutableListOf<Any?>()
        val handler = Handler(main)
        handler.post {
            seen += Thread.currentThread().name
            seen += Looper.myLooper()
            latch.countDown()
        }
        handler.postDelayed({ latch.countDown() }, 20)
        assertTrue(latch.await(5, TimeUnit.SECONDS))
        assertEquals("main", seen[0])
        assertSame(main, seen[1])
    }

    @Test
    fun `removed callbacks never run`() {
        val ran = AtomicBoolean(false)
        val handler = Handler(Looper.getMainLooper())
        val task = Runnable { ran.set(true) }
        handler.postDelayed(task, 100)
        assertTrue(handler.hasCallbacks(task))
        handler.removeCallbacks(task)
        Thread.sleep(200)
        assertFalse(ran.get())
    }

    @Test
    fun `Dispatchers Main runs on the main looper`() =
        runBlocking {
            val thread = withContext(Dispatchers.Main) { Thread.currentThread() }
            assertSame(Looper.getMainLooper().thread, thread)
            withContext(Dispatchers.Main) {
                delay(10)
                assertTrue(Looper.getMainLooper().isCurrentThread)
                withContext(Dispatchers.Main.immediate) { assertTrue(Looper.getMainLooper().isCurrentThread) }
            }
        }

    @Test
    fun `work posted to the main looper keeps the source of the thread that posted it`() {
        val handler = Handler(Looper.getMainLooper())
        val seen = java.util.concurrent.LinkedBlockingQueue<String>()
        SourceCaller.with(7L) {
            handler.post {
                seen += "post ${SourceCaller.current()}"
                // Posted again from the main looper: still the same source.
                handler.post { seen += "again ${SourceCaller.current()}" }
            }
        }
        handler.post { seen += "none ${SourceCaller.current()}" }
        val got = List(3) { seen.poll(5, TimeUnit.SECONDS) }.toSet()
        assertEquals(setOf("post 7", "again 7", "none null"), got)
        assertNull(SourceCaller.current())
    }

    @Test
    fun `Dispatchers Main keeps the caller's source, and a coroutine element carries it across threads`() =
        runBlocking {
            withContext(Dispatchers.IO + SourceCaller.element(9L)) {
                assertEquals(9L, SourceCaller.current())
                assertEquals(9L, withContext(Dispatchers.Main) { SourceCaller.current() })
            }
            val posted =
                SourceCaller.with(11L) {
                    val latch = CountDownLatch(1)
                    var seen: Long? = null
                    kotlinx.coroutines.GlobalScope.launch(Dispatchers.Main) {
                        seen = SourceCaller.current()
                        latch.countDown()
                    }
                    assertTrue(latch.await(5, TimeUnit.SECONDS))
                    seen
                }
            assertEquals(11L, posted)
        }

    @Test
    fun `system clock is monotonic`() {
        val a = SystemClock.elapsedRealtime()
        SystemClock.sleep(5)
        assertTrue(SystemClock.elapsedRealtime() >= a + 5)
        assertTrue(SystemClock.uptimeMillis() > 0)
    }
}
