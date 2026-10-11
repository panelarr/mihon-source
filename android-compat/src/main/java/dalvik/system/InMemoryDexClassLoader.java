// SPDX-License-Identifier: GPL-3.0-or-later

package dalvik.system;

import java.nio.ByteBuffer;

/** See {@link BaseDexClassLoader}: always throws. */
public final class InMemoryDexClassLoader extends BaseDexClassLoader {
    public InMemoryDexClassLoader(ByteBuffer dexBuffer, ClassLoader parent) {
        super(null, null, null, parent);
    }

    public InMemoryDexClassLoader(ByteBuffer[] dexBuffers, ClassLoader parent) {
        super(null, null, null, parent);
    }
}
