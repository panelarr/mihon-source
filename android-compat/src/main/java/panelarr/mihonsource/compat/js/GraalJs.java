// SPDX-License-Identifier: GPL-3.0-or-later

package panelarr.mihonsource.compat.js;

import java.io.OutputStream;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Engine;
import org.graalvm.polyglot.PolyglotException;
import org.graalvm.polyglot.Source;
import org.graalvm.polyglot.Value;
import org.graalvm.polyglot.proxy.ProxyArray;
import org.graalvm.polyglot.proxy.ProxyExecutable;
import org.graalvm.polyglot.proxy.ProxyObject;

/**
 * One JavaScript context on GraalJS, shared by the QuickJs and Duktape shims.
 *
 * <ul>
 *   <li>Nothing GraalVM starts until the first {@link #create()}; the engine is then shared by
 *       all contexts.</li>
 *   <li>Scripts get no host access, I/O or threads. Java objects are exposed only through
 *       {@link #set}, as plain JS objects whose functions call the bound interface.</li>
 *   <li>Every call is synchronized on the instance, and each evaluation is interrupted after
 *       {@link #TIMEOUT} (override with the {@code mihonsource.js.timeoutSeconds} system property).</li>
 *   <li>Results map like QuickJS: numbers to Integer when integral and in int range, otherwise
 *       Double ({@code numbersAsDouble} maps every number to Double, as Duktape does); booleans;
 *       strings; arrays to Object[] (recursively); undefined and null to null. Other JS objects
 *       map to null.</li>
 * </ul>
 */
public final class GraalJs implements AutoCloseable {
    public static final Duration TIMEOUT = Duration.ofSeconds(Long.getLong("mihonsource.js.timeoutSeconds", 30L));

    private static final String COMPILED_MAGIC = "GraalJS:1\n";

    private static volatile Engine engine;
    private static volatile ScheduledExecutorService watchdog;

    private final Context context;
    private final boolean numbersAsDouble;
    private boolean closed;

    /** A JavaScript error: the message, plus the JS stack trace when GraalJS has one. */
    public static final class ScriptError extends RuntimeException {
        private final String jsStackTrace;

        ScriptError(String message, String jsStackTrace, Throwable cause) {
            super(message, cause);
            this.jsStackTrace = jsStackTrace;
        }

        public String getJsStackTrace() {
            return jsStackTrace;
        }
    }

    private GraalJs(Context context, boolean numbersAsDouble) {
        this.context = context;
        this.numbersAsDouble = numbersAsDouble;
    }

    private static Engine engine() {
        Engine e = engine;
        if (e == null) {
            synchronized (GraalJs.class) {
                e = engine;
                if (e == null) {
                    e = Engine.newBuilder("js")
                        .option("engine.WarnInterpreterOnly", "false")
                        .out(OutputStream.nullOutputStream())
                        .err(OutputStream.nullOutputStream())
                        .build();
                    engine = e;
                }
            }
        }
        return e;
    }

    private static ScheduledExecutorService watchdog() {
        ScheduledExecutorService w = watchdog;
        if (w == null) {
            synchronized (GraalJs.class) {
                w = watchdog;
                if (w == null) {
                    w = Executors.newSingleThreadScheduledExecutor(r -> {
                        Thread t = new Thread(r, "js-watchdog");
                        t.setDaemon(true);
                        return t;
                    });
                    watchdog = w;
                }
            }
        }
        return w;
    }

    /**
     * Creates a context. Throws {@link IllegalStateException} with a readable message when
     * GraalJS can't start (missing from the classpath, or failing to initialise).
     */
    public static GraalJs create(boolean numbersAsDouble) {
        try {
            Context context = Context.newBuilder("js")
                .engine(engine())
                .allowAllAccess(false)
                .option("js.ecmascript-version", "latest")
                .build();
            return new GraalJs(context, numbersAsDouble);
        } catch (LinkageError | RuntimeException e) {
            throw new IllegalStateException("The JavaScript engine (GraalJS) could not start: " + e, e);
        }
    }

    private void checkOpen() {
        if (closed) throw new IllegalStateException("This JavaScript engine is closed");
    }

    private <T> T guarded(java.util.function.Supplier<T> action) {
        ScheduledFuture<?> timer = watchdog().schedule(() -> {
            try {
                context.interrupt(Duration.ofSeconds(5));
            } catch (Exception ignored) {
                // The evaluation finished or the context closed meanwhile.
            }
        }, TIMEOUT.toMillis(), TimeUnit.MILLISECONDS);
        try {
            return action.get();
        } catch (PolyglotException e) {
            throw translate(e);
        } finally {
            timer.cancel(false);
        }
    }

    private static RuntimeException translate(PolyglotException e) {
        if (e.isInterrupted() || e.isCancelled()) {
            return new ScriptError("JavaScript evaluation timed out after " + TIMEOUT.toSeconds() + " s", null, e);
        }
        if (e.isHostException()) {
            Throwable host = e.asHostException();
            return host instanceof RuntimeException r ? r : new RuntimeException(host);
        }
        StringBuilder stack = new StringBuilder();
        for (PolyglotException.StackFrame frame : e.getPolyglotStackTrace()) {
            if (frame.isGuestFrame()) stack.append("    at ").append(frame).append('\n');
        }
        String message = e.getMessage() == null ? "JavaScript error" : e.getMessage();
        return new ScriptError(message, stack.toString(), e);
    }

    public synchronized Object evaluate(String script, String fileName) {
        checkOpen();
        Source source = Source.newBuilder("js", script, fileName == null ? "?" : fileName).buildLiteral();
        return guarded(() -> toJava(context.eval(source)));
    }

    /** Checks the syntax and packs the script so {@link #execute(byte[])} can run it later. */
    public synchronized byte[] compile(String script, String fileName) {
        checkOpen();
        String name = fileName == null ? "?" : fileName;
        Source source = Source.newBuilder("js", script, name).buildLiteral();
        guarded(() -> context.parse(source));
        String packed = COMPILED_MAGIC + name.replace('\n', ' ') + "\n" + script;
        return packed.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public synchronized Object execute(byte[] compiled) {
        String packed = new String(compiled, java.nio.charset.StandardCharsets.UTF_8);
        if (!packed.startsWith(COMPILED_MAGIC)) throw new IllegalArgumentException("Not bytecode from compile()");
        int nameEnd = packed.indexOf('\n', COMPILED_MAGIC.length());
        return evaluate(packed.substring(nameEnd + 1), packed.substring(COMPILED_MAGIC.length(), nameEnd));
    }

    /** Exposes the methods of {@code type} on {@code object} as a global JS object. */
    public synchronized <T> void set(String name, Class<T> type, T object) {
        checkOpen();
        if (!type.isInterface()) throw new UnsupportedOperationException("Only interfaces can be bound. Received: " + type);
        Map<String, List<Method>> byName = new HashMap<>();
        for (Method method : type.getMethods()) {
            if (!Modifier.isStatic(method.getModifiers())) byName.computeIfAbsent(method.getName(), k -> new ArrayList<>()).add(method);
        }
        Map<String, Object> members = new HashMap<>();
        byName.forEach((methodName, overloads) -> members.put(methodName, (ProxyExecutable) args -> {
            Method method = pick(overloads, args.length);
            Object[] javaArgs = new Object[method.getParameterCount()];
            Class<?>[] types = method.getParameterTypes();
            for (int i = 0; i < javaArgs.length; i++) javaArgs[i] = i < args.length ? fromJs(args[i], types[i]) : null;
            try {
                return toJs(method.invoke(object, javaArgs));
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause();
                throw cause instanceof RuntimeException r ? r : new RuntimeException(cause);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException(e);
            }
        }));
        guarded(() -> {
            context.getBindings("js").putMember(name, ProxyObject.fromMap(members));
            return null;
        });
    }

    private static Method pick(List<Method> overloads, int argCount) {
        for (Method m : overloads) if (m.getParameterCount() == argCount) return m;
        return overloads.get(0);
    }

    /** Returns a Java proxy of {@code type} whose methods call the functions of a global JS object. */
    public synchronized <T> T get(String name, Class<T> type) {
        checkOpen();
        if (!type.isInterface()) throw new UnsupportedOperationException("Only interfaces can be proxied. Received: " + type);
        Value target = guarded(() -> context.getBindings("js").getMember(name));
        if (target == null || target.isNull()) {
            throw new IllegalArgumentException("A global JavaScript object called " + name + " was not found");
        }
        InvocationHandler handler = (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return switch (method.getName()) {
                    case "equals" -> proxy == args[0];
                    case "hashCode" -> System.identityHashCode(proxy);
                    default -> "JavaScriptObject(" + name + ")";
                };
            }
            synchronized (GraalJs.this) {
                checkOpen();
                Object[] jsArgs = args == null ? new Object[0] : new Object[args.length];
                for (int i = 0; i < jsArgs.length; i++) jsArgs[i] = toJs(args[i]);
                Value result = guarded(() -> {
                    Value fn = target.getMember(method.getName());
                    if (fn == null || !fn.canExecute()) {
                        throw new UnsupportedOperationException("JavaScript object " + name + " has no function " + method.getName());
                    }
                    return fn.execute(jsArgs);
                });
                return toReturnType(result, method.getReturnType());
            }
        };
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
    }

    private Object toReturnType(Value result, Class<?> type) {
        if (type == void.class) return null;
        if (type == Object.class) return toJava(result);
        if (result == null || result.isNull()) return type.isPrimitive() ? result.as(type) : null;
        return result.as(type);
    }

    private Object fromJs(Value value, Class<?> type) {
        if (type == Object.class) return toJava(value);
        return value.as(type);
    }

    Object toJava(Value v) {
        if (v == null || v.isNull()) return null;
        if (v.isBoolean()) return v.asBoolean();
        if (v.isNumber()) {
            if (!numbersAsDouble && v.fitsInInt()) return v.asInt();
            return v.fitsInDouble() ? v.asDouble() : Double.NaN;
        }
        if (v.isString()) return v.asString();
        if (v.hasArrayElements()) {
            long size = v.getArraySize();
            Object[] out = new Object[(int) Math.min(size, Integer.MAX_VALUE - 8)];
            for (int i = 0; i < out.length; i++) out[i] = toJava(v.getArrayElement(i));
            return out;
        }
        if (v.isProxyObject()) return v.asProxyObject();
        if (v.isHostObject()) return v.asHostObject();
        return null;
    }

    private static Object toJs(Object value) {
        if (value instanceof Object[] array) {
            Object[] converted = new Object[array.length];
            for (int i = 0; i < array.length; i++) converted[i] = toJs(array[i]);
            return ProxyArray.fromArray(converted);
        }
        if (value instanceof List<?> list) {
            List<Object> converted = new ArrayList<>(list.size());
            for (Object item : list) converted.add(toJs(item));
            return ProxyArray.fromList(converted);
        }
        if (value instanceof Character c) return String.valueOf(c);
        return value;
    }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        context.close(true);
    }
}
