// SPDX-License-Identifier: GPL-3.0-or-later

package app.cash.quickjs;

import java.io.Closeable;

/** The QuickJS Java API. Placeholder until the GraalJS backend lands: every call throws. */
public final class QuickJs implements Closeable {
    private QuickJs() {}

    public static QuickJs create() {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    public Object evaluate(String script) {
        return evaluate(script, "?");
    }

    public Object evaluate(String script, String fileName) {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    public byte[] compile(String sourceCode, String fileName) {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    public Object execute(byte[] bytecode) {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    public <T> void set(String name, Class<T> type, T object) {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    public <T> T get(String name, Class<T> type) {
        throw new QuickJsException("The JavaScript engine is not available yet");
    }

    @Override
    public void close() {}
}
