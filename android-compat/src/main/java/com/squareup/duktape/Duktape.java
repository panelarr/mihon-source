// SPDX-License-Identifier: GPL-3.0-or-later

package com.squareup.duktape;

import java.io.Closeable;
import panelarr.mihonsource.compat.js.GraalJs;

/**
 * The Duktape Java API for older extensions, running on GraalJS like QuickJs. As with Duktape,
 * every number comes back as a Double.
 */
public final class Duktape implements Closeable, AutoCloseable {
    private final GraalJs js;

    private Duktape(GraalJs js) {
        this.js = js;
    }

    public static Duktape create() {
        try {
            return new Duktape(GraalJs.create(true));
        } catch (IllegalStateException | LinkageError e) {
            throw new DuktapeException(e.getMessage(), e);
        }
    }

    public Object evaluate(String script) {
        return evaluate(script, "?");
    }

    public Object evaluate(String script, String fileName) {
        try {
            return js.evaluate(script, fileName);
        } catch (GraalJs.ScriptError e) {
            throw new DuktapeException(e.getMessage(), e);
        }
    }

    public <T> void set(String name, Class<T> type, T object) {
        try {
            js.set(name, type, object);
        } catch (GraalJs.ScriptError e) {
            throw new DuktapeException(e.getMessage(), e);
        }
    }

    public <T> T get(String name, Class<T> type) {
        try {
            return js.get(name, type);
        } catch (GraalJs.ScriptError e) {
            throw new DuktapeException(e.getMessage(), e);
        }
    }

    @Override
    public void close() {
        js.close();
    }
}
