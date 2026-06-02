/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataprepper;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;
import io.micrometer.core.instrument.Counter;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.breaker.CircuitBreaker;

import java.util.Objects;
import java.util.function.Function;

/**
 * An Armeria decorator that rejects HTTP requests immediately when the
 * circuit breaker is open. This prevents request body deserialization,
 * decompression, and protobuf parsing from consuming heap memory during
 * memory pressure.
 *
 * @since 2.12
 */
public class CircuitBreakerHttpDecorator extends SimpleDecoratingHttpService {
    static final String REQUESTS_CIRCUIT_BREAKER_REJECTED = "requestsCircuitBreakerRejected";
    private static final String REJECTION_MESSAGE =
            "Circuit breaker is open. Service is under memory pressure. Please retry later.";

    private final CircuitBreaker circuitBreaker;
    private final Counter rejectedRequestsCounter;

    private CircuitBreakerHttpDecorator(final HttpService delegate,
                                        final CircuitBreaker circuitBreaker,
                                        final Counter rejectedRequestsCounter) {
        super(delegate);
        this.circuitBreaker = Objects.requireNonNull(circuitBreaker);
        this.rejectedRequestsCounter = Objects.requireNonNull(rejectedRequestsCounter);
    }

    @Override
    public HttpResponse serve(final ServiceRequestContext ctx, final HttpRequest req) throws Exception {
        if (circuitBreaker.isOpen()) {
            rejectedRequestsCounter.increment();
            return HttpResponse.of(HttpStatus.SERVICE_UNAVAILABLE, MediaType.PLAIN_TEXT_UTF_8, REJECTION_MESSAGE);
        }
        return unwrap().serve(ctx, req);
    }

    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            final CircuitBreaker circuitBreaker, final PluginMetrics pluginMetrics) {
        Objects.requireNonNull(circuitBreaker);
        Objects.requireNonNull(pluginMetrics);
        final Counter counter = pluginMetrics.counter(REQUESTS_CIRCUIT_BREAKER_REJECTED);
        return delegate -> new CircuitBreakerHttpDecorator(delegate, circuitBreaker, counter);
    }
}
