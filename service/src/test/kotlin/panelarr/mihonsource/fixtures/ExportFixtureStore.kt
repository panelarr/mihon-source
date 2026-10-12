// SPDX-License-Identifier: GPL-3.0-or-later

@file:JvmName("ExportFixtureStore")

package panelarr.mihonsource.fixtures

import panelarr.mihonsource.extension.Signatures
import panelarr.mihonsource.store.IndexContentWarning
import java.nio.file.Files
import java.nio.file.Path

/**
 * Writes the fixture store to a directory for end-to-end tests outside this repo:
 * `v1/` offers the lib 1.6 test extension at 1.6.2 and `v2/` the same store with
 * 1.6.3, both signed with one test key, whose fingerprint is in `FINGERPRINT`. Serve
 * `v1` or `v2` at the same URL to see an update.
 *
 *     ./gradlew :service:exportFixtureStore -PfixtureStoreDir=/tmp/store
 */
fun main(args: Array<String>) {
    val out = Path.of(args.firstOrNull() ?: error("usage: ExportFixtureStore <dir>"))
    val key = TestSigningKey("fixture store")
    val work = Files.createTempDirectory("fixture-store")
    for ((dir, version, code) in listOf(Triple("v1", "1.6.2", 106002L), Triple("v2", "1.6.3", 106003L))) {
        val edited =
            TestExtensions.withManifest(TestExtensions.lib16, work.resolve("lib16-$version-unsigned.jar")) {
                it
                    .replace("android:versionCode=\"106002\"", "android:versionCode=\"$code\"")
                    .replace("android:versionName=\"1.6.2\"", "android:versionName=\"$version\"")
            }
        val signed = TestExtensions.signedJar(edited, key, work.resolve("lib16-$version.jar"))
        FixtureStore().use { store ->
            store.publish(
                key,
                listOf(
                    FixtureStore.Offer(
                        TestExtensions.LIB16_PKG,
                        "Lib16 Test",
                        version,
                        code,
                        "1.6",
                        Files.readAllBytes(signed),
                        asJar = true,
                        IndexContentWarning.CONTENT_WARNING_MIXED,
                        sources = listOf("Lib16 Test" to "en", "Lib16 Test" to "fr"),
                    ),
                ),
            )
            store.exportTo(out.resolve(dir))
        }
    }
    Files.writeString(out.resolve("FINGERPRINT"), Signatures.fingerprint(key.certificate) + "\n")
    work.toFile().deleteRecursively()
    println("Fixture store written to $out (v1, v2)")
}
