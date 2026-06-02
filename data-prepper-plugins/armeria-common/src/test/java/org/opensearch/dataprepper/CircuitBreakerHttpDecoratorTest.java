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
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import io.micrometer.core.instrument.Counter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.opensearch.dataprepper.metrics.PluginMetrics;
import org.opensearch.dataprepper.model.breaker.CircuitBreaker;

import java.util.function.Function;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.notNullValue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CircuitBreakerHttpDecoratorTest {

    @Mock
    private CircuitBreaker circuitBreaker;

    @Mock
    private PluginMetrics pluginMetrics;

    @Mock
    private Counter rejectedCounter;

    @Mock
    private HttpService delegate;

    @Mock
    private ServiceRequestContext ctx;

    @Mock
    private HttpRequest request;

    @BeforeEach
    void setUp() {
        lenient().when(pluginMetrics.counter(CircuitBreakerHttpDecorator.REQUESTS_CIRCUIT_BREAKER_REJECTED))
                .thenReturn(rejectedCounter);
    }

    @Test
    void serve_passes_through_when_circuit_breaker_is_closed() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(false);
        final HttpResponse expectedResponse = HttpResponse.of(HttpStatus.OK);
        when(delegate.serve(ctx, request)).thenReturn(expectedResponse);

        final Function<? super HttpService, ? extends HttpService> decorator =
                CircuitBreakerHttpDecorator.newDecorator(circuitBreaker, pluginMetrics);
        final HttpService decoratedService = decorator.apply(delegate);

        final HttpResponse response = decoratedService.serve(ctx, request);

        assertThat(response, equalTo(expectedResponse));
        verify(delegate).serve(ctx, request);
        verifyNoInteractions(rejectedCounter);
    }

    @Test
    void serve_returns_503_when_circuit_breaker_is_open() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(true);

        final Function<? super HttpService, ? extends HttpService> decorator =
                CircuitBreakerHttpDecorator.newDecorator(circuitBreaker, pluginMetrics);
        final HttpService decoratedService = decorator.apply(delegate);

        final HttpResponse response = decoratedService.serve(ctx, request);

        final AggregatedHttpResponse aggregated = response.aggregate().join();
        assertThat(aggregated.status(), equalTo(HttpStatus.SERVICE_UNAVAILABLE));
        verifyNoInteractions(delegate);
    }

    @Test
    void serve_increments_counter_when_circuit_breaker_is_open() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(true);

        final Function<? super HttpService, ? extends HttpService> decorator =
                CircuitBreakerHttpDecorator.newDecorator(circuitBreaker, pluginMetrics);
        final HttpService decoratedService = decorator.apply(delegate);

        decoratedService.serve(ctx, request);

        verify(rejectedCounter).increment();
    }

    @Test
    void serve_does_not_increment_counter_when_circuit_breaker_is_closed() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(delegate.serve(ctx, request)).thenReturn(HttpResponse.of(HttpStatus.OK));

        final Function<? super HttpService, ? extends HttpService> decorator =
                CircuitBreakerHttpDecorator.newDecorator(circuitBreaker, pluginMetrics);
        final HttpService decoratedService = decorator.apply(delegate);

        decoratedService.serve(ctx, request);

        verifyNoInteractions(rejectedCounter);
    }

    @Test
    void newDecorator_throws_on_null_circuit_breaker() {
        final PluginMetrics metrics = mock(PluginMetrics.class);
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerHttpDecorator.newDecorator(null, metrics));
    }

    @Test
    void newDecorator_throws_on_null_plugin_metrics() {
        final CircuitBreaker breaker = mock(CircuitBreaker.class);
        assertThrows(NullPointerException.class,
                () -> CircuitBreakerHttpDecorator.newDecorator(breaker, null));
    }

    @Test
    void newDecorator_returns_non_null_function() {
        final Function<? super HttpService, ? extends HttpService> decorator =
                CircuitBreakerHttpDecorator.newDecorator(circuitBreaker, pluginMetrics);
        assertThat(decorator, notNullValue());
    }
}
