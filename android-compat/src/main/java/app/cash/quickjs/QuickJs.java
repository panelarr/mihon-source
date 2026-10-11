// SPDX-License-Identifier: GPL-3.0-or-later

package app.cash.quickjs;

import java.io.Closeable;
import panelarr.mihonsource.compat.js.GraalJs;

/**
 * The QuickJS Java API, running on GraalJS. Each instance is its own JavaScript context and
 * every call on it is synchronized. GraalJS starts on the first {@link #create()}.
 *
 * <p>Results: numbers become Integer when integral and in int range, otherwise Double;
 * booleans, strings; arrays become Object[]; undefined and null become null. Other objects
 * become null. {@link #compile} checks the syntax and returns an opaque byte form of the
 * source that only {@link #execute} understands; it is not QuickJS bytecode.
 */
public final class QuickJs implements Closeable {
    private final GraalJs js;

    private QuickJs(GraalJs js) {
        this.js = js;
    }

    /** Creates a context. Throws QuickJsException when the engine can't start. */
    public static QuickJs create() {
        try {
            return new QuickJs(GraalJs.create(false));
        } catch (IllegalStateException | LinkageError e) {
            throw new QuickJsException(e.getMessage(), e);
        }
    }

    public Object evaluate(String script) {
        return evaluate(script, "?");
    }

    public Object evaluate(String script, String fileName) {
        try {
            return js.evaluate(script, fileName);
        } catch (GraalJs.ScriptError e) {
            throw new QuickJsException(e.getMessage(), e.getJsStackTrace());
        }
    }

    public byte[] compile(String sourceCode, String fileName) {
        try {
            return js.compile(sourceCode, fileName);
        } catch (GraalJs.ScriptError e) {
            throw new QuickJsException(e.getMessage(), e.getJsStackTrace());
        }
    }

    public Object execute(byte[] bytecode) {
        try {
            return js.execute(bytecode);
        } catch (GraalJs.ScriptError e) {
            throw new QuickJsException(e.getMessage(), e.getJsStackTrace());
        }
    }

    /** Exposes {@code object}'s {@code type} interface methods to JavaScript as the global {@code name}. */
    public <T> void set(String name, Class<T> type, T object) {
        try {
            js.set(name, type, object);
        } catch (GraalJs.ScriptError e) {
            throw new QuickJsException(e.getMessage(), e.getJsStackTrace());
        }
    }

    /** A proxy of {@code type} that calls the functions of the global JavaScript object {@code name}. */
    public <T> T get(String name, Class<T> type) {
        try {
            return js.get(name, type);
        } catch (GraalJs.ScriptError e) {
            throw new QuickJsException(e.getMessage(), e.getJsStackTrace());
        }
    }

    @Override
    public void close() {
        js.close();
    }
}
