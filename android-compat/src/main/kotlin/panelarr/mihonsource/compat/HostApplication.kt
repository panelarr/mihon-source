// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat

import android.app.ActivityManager
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.ApplicationInfo
import android.content.res.Resources
import android.os.Looper
import java.io.File
import java.util.logging.Logger

/** The host's Application: a ContextWrapper around [HostContext]. */
internal class HostApplication : Application() {
    init {
        attachBaseContext(HostContext(this))
    }
}

/** The Context behind [AndroidHost.application]. Every directory is created on demand. */
@Suppress("TooManyFunctions") // one override per Context member
internal class HostContext(
    private val application: Application,
) : Context() {
    private val log = Logger.getLogger("android.content.Context")
    private val activityManager by lazy { ActivityManager() }

    override fun getSharedPreferences(
        name: String,
        mode: Int,
    ): SharedPreferences = AndroidHost.sharedPreferences(name)

    override fun deleteSharedPreferences(name: String): Boolean = AndroidHost.deletePreferences(name)

    override fun getCacheDir(): File = AndroidHost.dir("cache").toFile()

    override fun getExternalCacheDir(): File = cacheDir

    override fun getCodeCacheDir(): File = AndroidHost.dir("cache/code").toFile()

    override fun getFilesDir(): File = AndroidHost.dir("files").toFile()

    override fun getNoBackupFilesDir(): File = AndroidHost.dir("files/no_backup").toFile()

    override fun getExternalFilesDir(type: String?): File = if (type == null) filesDir else AndroidHost.dir("files/$type").toFile()

    override fun getDataDir(): File = AndroidHost.dataDir.toFile()

    override fun getDir(
        name: String,
        mode: Int,
    ): File = AndroidHost.dir("app_$name").toFile()

    override fun getPackageName(): String = AndroidHost.PACKAGE_NAME

    override fun getApplicationContext(): Context = application

    override fun getApplicationInfo(): ApplicationInfo =
        ApplicationInfo().apply {
            packageName = AndroidHost.PACKAGE_NAME
            processName = AndroidHost.PACKAGE_NAME
            dataDir = AndroidHost.dataDir.toString()
            targetSdkVersion = android.os.Build.VERSION.SDK_INT
            minSdkVersion = MIN_SDK
        }

    override fun getSystemService(name: String): Any? = if (name == ACTIVITY_SERVICE) activityManager else null

    override fun getResources(): Resources = Resources.getSystem()

    override fun getClassLoader(): ClassLoader = HostContext::class.java.classLoader

    /** Nothing can handle an Intent here; the request is logged and dropped. */
    override fun startActivity(intent: Intent) {
        log.info { "Ignoring startActivity($intent): no activities on this host" }
    }

    override fun getMainLooper(): Looper = Looper.getMainLooper()

    private companion object {
        const val MIN_SDK = 26
    }
}
