// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.fixtures

import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.DockerImageName
import java.net.ServerSocket
import java.time.Duration

/**
 * A real headless Chromium in a container, on the host's network, so pages it loads reach
 * test sites on 127.0.0.1 at the same URLs the extensions' own HTTP clients use.
 *
 * It needs Docker with host networking (Linux, and CI): without Docker the test is skipped
 * locally, and fails in CI (`CI` set), so it always runs there.
 */
class HeadlessChromium private constructor(
    private val container: GenericContainer<*>,
    /** The DevTools port; [start] it again on this port to bring "the same" browser back. */
    val port: Int,
) : AutoCloseable {
    /** The DevTools address, as `MIHON_WEBVIEW_URL` takes it. */
    val url = "http://127.0.0.1:$port"

    override fun close() = container.stop()

    companion object {
        // Upstream headless Chromium, pinned by tag and digest. Bump both together.
        const val IMAGE =
            "chromedp/headless-shell:158.0.8127.18@sha256:830fa77e0602cb42e5cb2cbd9237ba27b649ad69d5fb82b54713aac8b7ab4f29"

        fun start(port: Int = ServerSocket(0).use { it.localPort }): HeadlessChromium {
            val docker = DockerClientFactory.instance().isDockerAvailable
            if (System.getenv("CI").isNullOrEmpty()) assumeTrue(docker, "Docker isn't available")
            check(docker) { "Docker isn't available, and CI needs it for this test" }
            val container =
                GenericContainer(DockerImageName.parse(IMAGE))
                    .withNetworkMode("host")
                    // Appended to the image's own flags; Chromium takes the last value of a repeated flag.
                    .withCommand("--remote-debugging-port=$port")
                    .waitingFor(Wait.forLogMessage(".*DevTools listening on.*", 1).withStartupTimeout(Duration.ofMinutes(2)))
            container.start()
            return HeadlessChromium(container, port)
        }
    }
}
