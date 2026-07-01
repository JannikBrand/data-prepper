/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataprepper.core.parser.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import jakarta.validation.constraints.NotNull;
import org.opensearch.dataprepper.model.types.ByteCount;

import java.time.Duration;

/**
 * Configuration for the heap circuit breaker.
 */
public class HeapCircuitBreakerConfig {
    public static final Duration DEFAULT_RESET = Duration.ofSeconds(1);
    private static final Duration DEFAULT_CHECK_INTERVAL = Duration.ofMillis(500);
    @NotNull
    @JsonProperty("usage")
    private ByteCount usage;

    @JsonProperty("reset")
    private Duration reset = DEFAULT_RESET;

    @JsonProperty("check_interval")
    private Duration checkInterval = DEFAULT_CHECK_INTERVAL;

    /**
     * Optional lower threshold at which an open breaker will close. When set, the
     * breaker opens on {@code used > usage} and only closes when {@code used < closeUsage}.
     * Prevents oscillation and gives GC room to free memory before admitting more work.
     * If unset, closes when {@code used <= usage} (original behavior).
     *
     * @since 2.15.1 (perfx patch)
     */
    @JsonProperty("close_usage")
    private ByteCount closeUsage;

    /**
     * If true, the breaker also samples heap on every {@link org.opensearch.dataprepper.model.breaker.CircuitBreaker#isOpen()}
     * call rather than only on the scheduled check. This closes the sampling-latency
     * gap between scheduled checks — under burst load, heap can grow substantially
     * within one {@link #checkInterval}, and without on-demand sampling every request
     * that arrives in that window sees a stale "closed" reading. Cost per call is one
     * native call to {@code MemoryMXBean.getHeapMemoryUsage()}.
     *
     * Default: true (perfx patch behavior). Set to false to restore the pre-patch
     * scheduled-only behavior.
     *
     * @since 2.15.1 (perfx patch)
     */
    @JsonProperty("on_demand_sampling")
    private Boolean onDemandSampling = Boolean.TRUE;

    /**
     * If true, calls {@link System#gc()} when the breaker trips. This is the
     * original upstream behavior. Under sustained memory pressure a stop-the-world
     * full GC serializes against request threads, filling TCP/HTTP-2 buffers during
     * the pause and often producing a bigger post-pause burst than what tripped the
     * breaker in the first place. Modern collectors (G1, ZGC) handle memory
     * pressure without explicit hints.
     *
     * Default: false (perfx patch behavior). Set to true to restore the pre-patch
     * behavior.
     *
     * @since 2.15.1 (perfx patch)
     */
    @JsonProperty("gc_on_trip")
    private Boolean gcOnTrip = Boolean.FALSE;

    /**
     * Gets the usage as a {@link ByteCount}. If the current Java heap usage
     * exceeds this value then the circuit breaker will be open.
     *
     * @return Usage threshold
     * @since 2.1
     */
    public ByteCount getUsage() {
        return usage;
    }

    /**
     * Gets the reset timeout. After tripping the circuit breaker, no new
     * checks until after this time has passed.
     *
     * @return The duration
     * @since 2.1
     */
    public Duration getReset() {
        return reset;
    }

    /**
     * Gets the check interval. This is the time between checks of the heap size.
     *
     * @return The check interval as a duration
     * @since 2.1
     */
    public Duration getCheckInterval() {
        return checkInterval;
    }

    /**
     * Gets the lower threshold at which an open breaker will close. Returns
     * {@code null} when unset — in that case the breaker closes when
     * {@code used <= usage} (original semantics).
     *
     * @return The close usage threshold, or {@code null}
     * @since 2.15.1 (perfx patch)
     */
    public ByteCount getCloseUsage() {
        return closeUsage;
    }

    /**
     * Whether the breaker should sample heap on every isOpen() call in addition
     * to the scheduled check. Defaults to true.
     *
     * @since 2.15.1 (perfx patch)
     */
    public boolean isOnDemandSampling() {
        return onDemandSampling == null || onDemandSampling;
    }

    /**
     * Whether the breaker should call {@code System.gc()} on trip. Defaults to false.
     *
     * @since 2.15.1 (perfx patch)
     */
    public boolean isGcOnTrip() {
        return gcOnTrip != null && gcOnTrip;
    }
}
