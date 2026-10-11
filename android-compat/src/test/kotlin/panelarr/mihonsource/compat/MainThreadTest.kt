// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    fun `system clock is monotonic`() {
        val a = SystemClock.elapsedRealtime()
        SystemClock.sleep(5)
        assertTrue(SystemClock.elapsedRealtime() >= a + 5)
        assertTrue(SystemClock.uptimeMillis() > 0)
    }
}
