// SPDX-License-Identifier: GPL-3.0-or-later

package android.content.pm;

/** The public fields of Android's ApplicationInfo that extensions read. */
public class ApplicationInfo {
    public static final int FLAG_DEBUGGABLE = 1 << 1;

    public String packageName;
    public String dataDir;
    public String sourceDir;
    public String publicSourceDir;
    public String nativeLibraryDir;
    public String processName;
    public String className;
    public String name;
    public int flags;
    public int uid;
    public int minSdkVersion;
    public int targetSdkVersion;
    public boolean enabled = true;

    public ApplicationInfo() {}

    public ApplicationInfo(ApplicationInfo orig) {
        packageName = orig.packageName;
        dataDir = orig.dataDir;
        sourceDir = orig.sourceDir;
        publicSourceDir = orig.publicSourceDir;
        nativeLibraryDir = orig.nativeLibraryDir;
        processName = orig.processName;
        className = orig.className;
        name = orig.name;
        flags = orig.flags;
        uid = orig.uid;
        minSdkVersion = orig.minSdkVersion;
        targetSdkVersion = orig.targetSdkVersion;
        enabled = orig.enabled;
    }

    @Override
    public String toString() {
        return "ApplicationInfo{" + packageName + "}";
    }
}
