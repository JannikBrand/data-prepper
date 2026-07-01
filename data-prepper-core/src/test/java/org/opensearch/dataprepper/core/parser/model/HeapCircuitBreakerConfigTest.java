/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

package org.opensearch.dataprepper.core.parser.model;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.opensearch.dataprepper.model.types.ByteCount;
import org.opensearch.dataprepper.pipeline.parser.ByteCountDeserializer;
import org.opensearch.dataprepper.pipeline.parser.DataPrepperDurationDeserializer;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;
import static org.hamcrest.MatcherAssert.assertThat;

class HeapCircuitBreakerConfigTest {
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper(new YAMLFactory());

        final SimpleModule simpleModule = new SimpleModule()
                .addDeserializer(ByteCount.class, new ByteCountDeserializer())
                .addDeserializer(Duration.class, new DataPrepperDurationDeserializer());
        objectMapper.registerModule(simpleModule);
    }

    @Test
    void deserialize_heap_without_reset() throws IOException {
        final InputStream resourceStream = this.getClass().getResourceAsStream("heap_with_reset.yaml");

        final HeapCircuitBreakerConfig config = objectMapper.readValue(resourceStream, HeapCircuitBreakerConfig.class);

        assertThat(config, notNullValue());
        assertThat(config.getUsage(), notNullValue());
        assertThat(config.getUsage().getBytes(), equalTo(24L));
        assertThat(config.getReset(), notNullValue());
        assertThat(config.getReset(), equalTo(Duration.ofSeconds(3)));
    }

    @Test
    void deserialize_heap_without_reset_configured() throws IOException {
        final InputStream resourceStream = this.getClass().getResourceAsStream("heap_without_reset.yaml");

        final HeapCircuitBreakerConfig config = objectMapper.readValue(resourceStream, HeapCircuitBreakerConfig.class);

        assertThat(config, notNullValue());
        assertThat(config.getUsage(), notNullValue());
        assertThat(config.getUsage().getBytes(), equalTo(24L));
        assertThat(config.getReset(), notNullValue());
        assertThat(config.getReset(), equalTo(HeapCircuitBreakerConfig.DEFAULT_RESET));
    }

    /**
     * Perfx-patch defaults when the new fields are absent from config:
     *   close_usage           -> null (falls back to strict close semantics)
     *   on_demand_sampling    -> true
     *   gc_on_trip            -> false
     */
    @Test
    void perfx_new_fields_default_when_absent() throws IOException {
        final InputStream resourceStream = this.getClass().getResourceAsStream("heap_with_reset.yaml");

        final HeapCircuitBreakerConfig config = objectMapper.readValue(resourceStream, HeapCircuitBreakerConfig.class);

        assertThat(config.getCloseUsage(), equalTo(null));
        assertThat(config.isOnDemandSampling(), equalTo(true));
        assertThat(config.isGcOnTrip(), equalTo(false));
    }

    @Test
    void perfx_new_fields_deserialize_when_present() throws IOException {
        final InputStream resourceStream = this.getClass().getResourceAsStream("heap_perfx_all_fields.yaml");

        final HeapCircuitBreakerConfig config = objectMapper.readValue(resourceStream, HeapCircuitBreakerConfig.class);

        assertThat(config.getUsage().getBytes(), equalTo(1024L));
        assertThat(config.getCloseUsage(), notNullValue());
        assertThat(config.getCloseUsage().getBytes(), equalTo(512L));
        assertThat(config.getCheckInterval(), equalTo(Duration.ofMillis(250)));
        assertThat(config.isOnDemandSampling(), equalTo(true));
        assertThat(config.isGcOnTrip(), equalTo(false));
    }
}