/*
 * Copyright (c) 2014-2015 VMware, Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.  You may obtain a copy of
 * the License at http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, without warranties or
 * conditions of any kind, EITHER EXPRESS OR IMPLIED.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.vmware.xenon.common;

import java.io.ByteArrayOutputStream;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Runtime utility functions
 */
public final class Utils {

    public static final String PROPERTY_NAME_PREFIX = "xenon.";
    public static final Charset CHARSET_OBJECT = StandardCharsets.UTF_8;
    public static final String CHARSET = "UTF-8";
    public static final String UI_DIRECTORY_NAME = "ui";
    public static final String PROPERTY_NAME_TIME_COMPARISON = "timeComparisonEpsilonMicros";

    /**
     * Number of IO threads is used for the HTTP selector event processing. Most of the
     * work is done in the context of the service host executor so we just use a couple of threads.
     * Performance work indicates any more threads do not help, rather, they hurt throughput
     */
    public static final int DEFAULT_IO_THREAD_COUNT = Math.min(2, Runtime.getRuntime()
            .availableProcessors());

    /**
     * Number of threads used for the service host executor and shared across service instances.
     * We add to the total count since the executor will also be used to process I/O selector
     * events, which will consume threads. Using much more than the number of processors hurts
     * operation processing throughput.
     */
    public static final int DEFAULT_THREAD_COUNT = Math.max(4, Runtime.getRuntime()
            .availableProcessors());

    /**
     * See {@link #setTimeDriftThreshold(long)}
     */
    public static final long DEFAULT_TIME_DRIFT_THRESHOLD_MICROS = TimeUnit.SECONDS.toMicros(1);

    private static final AtomicLong previousTimeValue = new AtomicLong();
    private static long timeDriftThresholdMicros = DEFAULT_TIME_DRIFT_THRESHOLD_MICROS;


    private static final ConcurrentMap<String, String> KINDS = new ConcurrentSkipListMap<>();
    private static final ConcurrentMap<String, Class<?>> KIND_TO_TYPE = new ConcurrentSkipListMap<>();

    private Utils() {

    }

    public static String toString(Throwable t) {
        if (t == null) {
            return null;
        } else {
            StringWriter writer = new StringWriter();
            try (PrintWriter printer = new PrintWriter(writer)) {
                t.printStackTrace(printer);
            }

            return writer.toString();
        }
    }

    public static String toString(Map<?, Throwable> exceptions) {
        StringWriter writer = new StringWriter();
        try (PrintWriter printer = new PrintWriter(writer)) {
            for (Throwable t : exceptions.values()) {
                t.printStackTrace(printer);
            }
        }

        return writer.toString();
    }

    public static void log(Class<?> type, String classOrUri, Level level, String fmt,
                           Object... args) {
        Logger lg = Logger.getLogger(type.getName());
        log(lg, 3, classOrUri, level, () -> String.format(fmt, args));
    }

    public static void log(Class<?> type, String classOrUri, Level level,
                           Supplier<String> messageSupplier) {
        Logger lg = Logger.getLogger(type.getName());
        log(lg, 3, classOrUri, level, messageSupplier);
    }

    public static void log(Logger lg, Integer nestingLevel, String classOrUri, Level level,
                           String fmt, Object... args) {
        log(lg, nestingLevel, classOrUri, level, () -> String.format(fmt, args));
    }

    public static void log(Logger lg, Integer nestingLevel, String classOrUri, Level level,
                           Supplier<String> messageSupplier) {
        if (!lg.isLoggable(level)) {
            return;
        }
        if (nestingLevel == null) {
            nestingLevel = 2;
        }

        String message = messageSupplier.get();
        LogRecord lr = new LogRecord(level, message);

        StackTraceElement frame = StackFrameExtractor.getStackFrameAt(nestingLevel);
        if (frame != null) {
            lr.setSourceMethodName(frame.getMethodName());
        }

        lr.setSourceClassName(classOrUri);
        lr.setLoggerName(lg.getName());

        lg.log(lr);
    }

    public static void logWarning(String fmt, Object... args) {
        Logger.getAnonymousLogger().warning(String.format(fmt, args));
    }

    public static String toDocumentKind(Class<?> type) {
        return type.getCanonicalName().replace('.', ':');
    }

    /**
     * Obtain the canonical name for a class from the entry in the documentKind field
     */
    public static String fromDocumentKind(String kind) {
        return kind.replace(':', '.');
    }

    /**
     * Registers mapping between a type and document kind string the runtime
     * will use for all services with that state type
     */
    public static String registerKind(Class<?> type, String kind) {
        KIND_TO_TYPE.put(kind, type);
        return KINDS.put(type.getName(), kind);
    }

    /**
     * Obtain the class for the specified kind. Only classes registered via
     * {@code Utils#registerKind(Class, String)} will be returned
     */
    public static Class<?> getTypeFromKind(String kind) {
        return KIND_TO_TYPE.get(kind);
    }

    /**
     * Builds a kind string from a type. It uses a cache to lookup the type to kind
     * mapping. The mapping can be overridden with {@code Utils#registerKind(Class, String)}
     */
    public static String buildKind(Class<?> type) {
        return KINDS.computeIfAbsent(type.getName(), name -> toDocumentKind(type));
    }

    public static Object setJsonProperty(Object body, String fieldName, String fieldValue) {
        JsonObject jo;
        if (body instanceof JsonObject) {
            jo = (JsonObject) body;
        } else {
            jo = new JsonParser().parse((String) body).getAsJsonObject();
        }
        jo.remove(fieldName);
        if (fieldValue != null) {
            jo.addProperty(fieldName, fieldValue);
        }

        return jo;
    }

    /**
     * Compresses byte[] to gzip byte[]
     */
    private static byte[] compressGZip(byte[] input) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zos = new GZIPOutputStream(out)) {
            zos.write(input, 0, input.length);
        }
        return out.toByteArray();
    }

    /**
     * Compresses text to gzip byte buffer.
     */
    public static ByteBuffer compressGZip(String text) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream zos = new GZIPOutputStream(out)) {
            byte[] bytes = text.getBytes(CHARSET);
            zos.write(bytes, 0, bytes.length);
        }

        return ByteBuffer.wrap(out.toByteArray());
    }


    private static final class StackFrameExtractor {
        private static final Method getStackTraceElement;

        static {
            Method m = null;
            try {
                m = Throwable.class.getDeclaredMethod("getStackTraceElement", int.class);
                m.setAccessible(true);
            } catch (Exception ignore) {

            }

            getStackTraceElement = m;
        }

        static StackTraceElement getStackFrameAt(int i) {
            Exception exception = new Exception();
            if (getStackTraceElement == null) {
                StackTraceElement[] stackTrace = exception.getStackTrace();
                if (stackTrace.length > i + 1) {
                    return exception.getStackTrace()[i + 1];
                } else {
                    return null;
                }
            }

            try {
                return (StackTraceElement) getStackTraceElement.invoke(exception, i + 1);
            } catch (Exception e) {
                StackTraceElement[] stackTrace = exception.getStackTrace();
                if (stackTrace.length > i + 1) {
                    return exception.getStackTrace()[i + 1];
                } else {
                    return null;
                }
            }
        }
    }

    /**
     * Adds the supplied argument to the value from {@link #getSystemNowMicrosUtc()} and returns
     * an absolute expiration time in the future
     */
    public static long fromNowMicrosUtc(long deltaMicros) {
        return getSystemNowMicrosUtc() + deltaMicros;
    }

    /**
     * Expects an absolute time, in microseconds since Epoch and returns true if the value represents
     * a time before the current system time
     */
    public static boolean beforeNow(long microsUtc) {
        return getSystemNowMicrosUtc() >= microsUtc;
    }

    /**
     * Returns the current time in microseconds, since Unix Epoch. This method can return the
     * same value on consecutive calls. See {@link #getNowMicrosUtc()} for an alternative but
     * with potential for drift from wall clock time
     */
    public static long getSystemNowMicrosUtc() {
        return System.currentTimeMillis() * 1000;
    }

    /**
     * Return wall clock time, in microseconds since Unix Epoch (1/1/1970 UTC midnight). This
     * functions guarantees time always moves forward, but it does not guarantee it does so in fixed
     * intervals.
     *
     * @return
     */
    public static long getNowMicrosUtc() {
        long now = System.currentTimeMillis() * 1000;
        long time = previousTimeValue.getAndIncrement();

        // Only set time if current time is greater than our stored time.
        if (now > time) {
            // This CAS can fail; getAndIncrement() ensures no value is returned twice.
            previousTimeValue.compareAndSet(time + 1, now);
            return previousTimeValue.getAndIncrement();
        } else if (time - now > timeDriftThresholdMicros) {
            throw new IllegalStateException("Time drift is " + (time - now));
        }

        return time;
    }

    /**
     * Infrastructure use only, do *not* use outside tests.
     * Set the upper bound between wall clock time as reported by {@link System#currentTimeMillis()}
     * and the time reported by {@link #getNowMicrosUtc()} (when both converted to micros).
     * The current time value will be reset to latest wall clock time so this call must be avoided
     * at all costs in a production system (it might make {@link #getNowMicrosUtc()} return a
     * smaller value than previous calls
     */
    public static void setTimeDriftThreshold(long micros) {
        timeDriftThresholdMicros = micros;
        previousTimeValue.set(TimeUnit.MILLISECONDS.toMicros(System.currentTimeMillis()));
    }
}