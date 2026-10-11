// SPDX-License-Identifier: GPL-3.0-or-later

package dalvik.system;

import java.io.File;

/** Dex class loading can't work on a JVM; constructing any dex class loader throws. */
public class BaseDexClassLoader extends ClassLoader {
    static final String UNSUPPORTED = "Loading dex files is not available in mihon-source";

    public BaseDexClassLoader(String dexPath, File optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(parent);
        throw new UnsupportedOperationException(UNSUPPORTED);
    }

    public String findLibrary(String name) {
        return null;
    }
}
