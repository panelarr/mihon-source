// SPDX-License-Identifier: GPL-3.0-or-later

package dalvik.system;

/** See {@link BaseDexClassLoader}: always throws. */
public class DexClassLoader extends BaseDexClassLoader {
    public DexClassLoader(String dexPath, String optimizedDirectory, String librarySearchPath, ClassLoader parent) {
        super(dexPath, null, librarySearchPath, parent);
    }
}
