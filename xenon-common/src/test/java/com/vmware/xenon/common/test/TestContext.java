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

package com.vmware.xenon.common.test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
import java.util.logging.Logger;

import com.vmware.xenon.common.DeferredResult;

/**
 * Test context used for synchronous tests. Provides an isolated version of the
 * {@code VerificationHost} testStart and testWait methods and allows nesting
 */
public class TestContext {

    public static final Duration DEFAULT_WAIT_DURATION = Duration.ofSeconds(30);

    // default interval to perform waitFor logic. set a tenth of the default maintenance interval
    public static final Duration DEFAULT_INTERVAL_DURATION = Duration.of(60000 / 10, ChronoUnit.MICROS);

    private CountDownLatch latch;

    private Duration interval = DEFAULT_INTERVAL_DURATION;

    private Duration duration;

    private volatile Throwable error;

    private boolean started;

    private int initialCount;

    private Instant creationInstant;

    private Instant finishInstant;

    private String name;

    public static class WaitConfig {
        private Duration interval = DEFAULT_INTERVAL_DURATION;
        private Duration duration;

        public WaitConfig setInterval(Duration interval) {
            this.interval = interval;
            return this;
        }

        public WaitConfig setDuration(Duration duration) {
            this.duration = duration;
            return this;
        }
    }

    /**
     * Consider using {@link #TestContext(int, Duration)}
     * This method exists for backward compatibility, and may be deprecated/deleted in future.
     */
    public static TestContext create(int count, long expIntervalMicros) {
        return new TestContext(count, Duration.of(expIntervalMicros, ChronoUnit.MICROS));
    }

    public TestContext(int count, Duration duration) {
        this.latch = new CountDownLatch(count);
        this.duration = duration;
        this.initialCount = count;
        this.creationInstant = Instant.now();
        StackTraceElement[] stack = new Exception().getStackTrace();
        final int parentMethodIndex = 2;
        this.name = stack[parentMethodIndex].getMethodName()
                + ":" + stack[parentMethodIndex].getLineNumber();
    }

    public void complete() {
        this.started = true;
        this.latch.countDown();
    }

    public void fail(Throwable e) {
        this.started = true;
        this.error = e;
        this.latch.countDown();
    }

    public TestContext setTestName(String name) {
        this.name = name;
        return this;
    }

    public TestContext setCount(int count) {
        if (this.started) {
            throw new RuntimeException(String.format(
                    "%s has already started. count=%d", getClass().getSimpleName(), this.latch.getCount()));
        }
        this.latch = new CountDownLatch(count);
        return this;
    }

    public TestContext setTimeout(Duration duration) {
        if (this.started) {
            throw new RuntimeException(String.format(
                    "%s has already started. count=%d", getClass().getSimpleName(), this.latch.getCount()));
        }
        this.duration = duration;
        return this;
    }

    public TestContext setCheckInterval(Duration interval) {
        this.interval = interval;
        return this;
    }

    public void await() {
        await(() -> {
        });
    }

    public void await(DeferredResult<?> deferredResult) {
        await(() -> {
            if (deferredResult.isDone()) {
                this.completeIteration();
            }
        });
    }

    public void await(ExecutableBlock beforeCheck) {

        ExceptionTestUtils.executeSafely(() -> {

            if (this.latch == null) {
                throw new IllegalStateException("This context is already used");
            }

            Instant waitStartInstant = Instant.now();
            Instant waitExpirationInstant = waitStartInstant.plus(this.duration);
            long countAtAwait = this.latch.getCount();

            // keep polling latch every interval
            while (waitExpirationInstant.isAfter(Instant.now())) {
                beforeCheck.execute();
                if (this.latch.await(this.interval.toNanos(), TimeUnit.NANOSECONDS)) {
                    break;
                }
            }

            Instant now = Instant.now();
            if (waitExpirationInstant.isBefore(now)) {
                LocalDateTime startAtLocal = LocalDateTime.ofInstant(waitStartInstant,
                        ZoneId.systemDefault());
                LocalDateTime expireAtLocal = LocalDateTime.ofInstant(waitExpirationInstant,
                        ZoneId.systemDefault());

                Duration actualDuration = Duration.between(waitStartInstant, now);
                String msg = String.format("%s has expired. [start=%s(%s), expire=%s(%s), " +
                                "durationGiven=%s, durationActual=%s, " +
                                "countAtInit=%d, countAtAwait=%d, countNow=%d]",
                        getClass().getSimpleName(),
                        waitStartInstant.toEpochMilli(), startAtLocal,
                        waitExpirationInstant.toEpochMilli(), expireAtLocal,
                        this.duration, actualDuration,
                        this.initialCount, countAtAwait, this.latch.getCount());
                throw new TimeoutException(msg);
            }

            this.finishInstant = now;

            // prevent this latch from being reused
            this.latch = null;

            if (this.error != null) {
                throw this.error;
            }
        });
    }

    /**
     * Consider using {@link #complete()}.
     * This method exists for backward compatibility, and may be deprecated/deleted in future.
     */
    public void completeIteration() {
        complete();
    }

    /**
     * Consider using {@link #fail(Throwable)}.
     * This method exists for backward compatibility, and may be deprecated/deleted in future.
     */
    public void failIteration(Throwable e) {
        fail(e);
    }

    public <T> BiConsumer<T, ? super Throwable> getCompletionDeferred() {
        return (ignore, e) -> {
            if (e != null) {
                if (e instanceof CompletionException) {
                    e = e.getCause();
                }
                failIteration(e);
                return;
            }
            completeIteration();
        };
    }

    public void logBefore() {
        String msg = String.format("%s count = %d",
                this.name,
                this.initialCount);
        Logger.getAnonymousLogger().info(msg);
    }

    public double logAfter() {
        double delta = Duration.between(this.creationInstant, this.finishInstant).toNanos();
        delta /= (double) TimeUnit.SECONDS.toNanos(1);
        double thpt = ((double) this.initialCount) / delta;
        String msg = String.format("%s throughput: %f, count = %d", this.name, thpt,
                this.initialCount);
        Logger.getAnonymousLogger().info(msg);
        return thpt;
    }
}