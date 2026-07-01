/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataprepper.core.breaker;

import io.micrometer.core.instrument.Metrics;
import org.opensearch.dataprepper.core.parser.model.HeapCircuitBreakerConfig;
import org.opensearch.dataprepper.model.breaker.CircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * An implementation of {@link CircuitBreaker} which checks against heap usage.
 *
 * <p>Perfx patch changes on top of upstream 2.15.1:
 * <ul>
 *   <li>On-demand heap sampling in {@link #isOpen()} — the scheduled check every
 *       {@code checkInterval} leaves a window in which heap can grow beyond the
 *       threshold before the breaker notices. On-demand sampling closes that gap.
 *       Enabled by default; controlled via {@code on_demand_sampling}.</li>
 *   <li>{@code System.gc()} on trip is disabled by default — a stop-the-world full
 *       GC under memory pressure serializes against request threads and typically
 *       makes things worse. Controlled via {@code gc_on_trip}.</li>
 *   <li>Two-threshold hysteresis via {@code close_usage} — the breaker opens on
 *       {@code used &gt; usage} but only closes when {@code used &lt; close_usage}.
 *       Prevents oscillation when heap sits near the threshold. Optional; falls
 *       back to strict {@code used &lt;= usage} closing when not set.</li>
 * </ul>
 *
 * @since 2.1
 */
class HeapCircuitBreaker implements InnerCircuitBreaker, AutoCloseable {
    private static final Logger LOG = LoggerFactory.getLogger(HeapCircuitBreaker.class);
    public static final int OPEN_METRIC_VALUE = 1;
    public static final int CLOSED_METRIC_VALUE = 0;

    private final MemoryMXBean memoryMXBean;
    private final long openUsageBytes;
    /** Threshold below which an open breaker closes. Equals {@code openUsageBytes} if not configured (original behavior). */
    private final long closeUsageBytes;
    private final Duration resetPeriod;
    private final boolean onDemandSampling;
    private final boolean gcOnTrip;
    private final Lock lock;
    private final AtomicInteger openGauge;
    private final ScheduledExecutorService scheduledExecutorService;
    /** Last observed heap-used, cached so {@link #isOpen()} on-demand path can avoid duplicate reads within the same sample window. */
    private final AtomicLong lastUsedBytes = new AtomicLong(0L);
    private volatile boolean open;
    private volatile Instant resetTime;

    HeapCircuitBreaker(final HeapCircuitBreakerConfig circuitBreakerConfig) {
        this(circuitBreakerConfig, ManagementFactory.getMemoryMXBean());
    }

    HeapCircuitBreaker(final HeapCircuitBreakerConfig circuitBreakerConfig, final MemoryMXBean memoryMXBean) {
        Objects.requireNonNull(circuitBreakerConfig);
        Objects.requireNonNull(circuitBreakerConfig.getUsage());

        openUsageBytes = circuitBreakerConfig.getUsage().getBytes();
        if(openUsageBytes <= 0)
            throw new IllegalArgumentException("Bytes usage must be positive.");

        // close_usage is optional. When unset, close semantics match the original
        // upstream code: closed when used <= usage (i.e. not > usage).
        if(circuitBreakerConfig.getCloseUsage() != null) {
            final long configuredCloseBytes = circuitBreakerConfig.getCloseUsage().getBytes();
            if(configuredCloseBytes <= 0)
                throw new IllegalArgumentException("close_usage bytes must be positive when set.");
            if(configuredCloseBytes > openUsageBytes)
                throw new IllegalArgumentException("close_usage must be less than or equal to usage.");
            closeUsageBytes = configuredCloseBytes;
        } else {
            closeUsageBytes = openUsageBytes;
        }

        resetPeriod = Objects.requireNonNull(circuitBreakerConfig.getReset());
        this.onDemandSampling = circuitBreakerConfig.isOnDemandSampling();
        this.gcOnTrip = circuitBreakerConfig.isGcOnTrip();
        this.memoryMXBean = memoryMXBean;
        open = false;
        lock = new ReentrantLock();
        resetTime = Instant.MIN;

        Metrics.gauge("core.circuitBreakers.heap.memoryUsage", this, cb -> getUsedMemoryBytes());
        openGauge = Metrics.gauge("core.circuitBreakers.heap.open", new AtomicInteger(0));

        final Duration checkInterval = Objects.requireNonNull(circuitBreakerConfig.getCheckInterval());
        scheduledExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledExecutorService
                        .scheduleAtFixedRate(this::checkMemory, 0L, checkInterval.toMillis(), TimeUnit.MILLISECONDS);

        if(closeUsageBytes < openUsageBytes) {
            LOG.info("Circuit breaker heap limits: open at {} bytes, close at {} bytes (hysteresis). on_demand_sampling={} gc_on_trip={}",
                    openUsageBytes, closeUsageBytes, onDemandSampling, gcOnTrip);
        } else {
            LOG.info("Circuit breaker heap limit is set to {} bytes. on_demand_sampling={} gc_on_trip={}",
                    openUsageBytes, onDemandSampling, gcOnTrip);
        }
    }

    @Override
    public boolean isOpen() {
        if (!onDemandSampling) {
            return open;
        }
        // Fast path: already open (from scheduled or a prior on-demand check).
        // Also: don't try to close on-demand — closing must respect resetPeriod,
        // which is the scheduled path's job.
        if (open) {
            return true;
        }
        // Cheap live read to catch bursts between scheduled samples.
        final long used;
        try {
            used = memoryMXBean.getHeapMemoryUsage().getUsed();
        } catch (final Exception e) {
            // Preserve upstream contract: on MXBean failure, return the last known state.
            return false;
        }
        lastUsedBytes.set(used);
        if (used > openUsageBytes) {
            // Trip via the same code path as the scheduled check so state and
            // metrics remain consistent (single writer under lock).
            tripOpen(used);
            return true;
        }
        return false;
    }

    private void checkMemory() {
        final boolean previousOpen = open;

        if(previousOpen && Instant.now().compareTo(resetTime) < 0) {
            return;
        }

        final long usedMemoryBytes;
        try {
            usedMemoryBytes = getUsedMemoryBytes();
        } catch (final Exception e) {
            // Matches upstream behavior: if we cannot read heap usage, do not change state.
            return;
        }
        lastUsedBytes.set(usedMemoryBytes);

        if(previousOpen) {
            // Only close when heap has genuinely fallen below the close threshold.
            // When close_usage is unset, closeUsageBytes == openUsageBytes and the
            // condition below reduces to the original "used <= usage" semantics.
            if(usedMemoryBytes < closeUsageBytes || (closeUsageBytes == openUsageBytes && usedMemoryBytes <= openUsageBytes)) {
                open = false;
                openGauge.set(CLOSED_METRIC_VALUE);
                LOG.info("Circuit breaker closed. {} used memory bytes below close threshold {}", usedMemoryBytes, closeUsageBytes);
            }
            // else: stay open, next scheduled check will re-evaluate after resetPeriod.
            return;
        }

        // Currently closed: check whether to trip.
        if(usedMemoryBytes > openUsageBytes) {
            tripOpen(usedMemoryBytes);
        }
    }

    /**
     * Idempotent trip: multiple concurrent callers (scheduled thread + on-demand
     * request threads) can invoke this. The lock ensures only one performs the
     * state transition and side effects (log, gc, metric).
     */
    private void tripOpen(final long usedMemoryBytes) {
        if (open) return;
        lock.lock();
        try {
            if (open) return;
            open = true;
            resetTime = Instant.now().plus(resetPeriod);
            openGauge.set(OPEN_METRIC_VALUE);
            LOG.info("Circuit breaker tripped and open. {} used memory bytes > {} configured", usedMemoryBytes, openUsageBytes);
            if (gcOnTrip) {
                // Legacy behavior — see class Javadoc for why this is off by default.
                System.gc();
            }
        } finally {
            lock.unlock();
        }
    }

    private long getUsedMemoryBytes() {
        return memoryMXBean.getHeapMemoryUsage().getUsed();
    }

    @Override
    public void close() throws Exception {
        scheduledExecutorService.shutdown();
    }
}
