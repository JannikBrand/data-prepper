/*
 * Copyright OpenSearch Contributors
 * SPDX-License-Identifier: Apache-2.0
 */

package com.amazon.dataprepper.model.trace;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;

/**
 * The default implementation of {@link TraceGroupFields}, the attributes associated with an entire trace.
 *
 * @since 1.2
 */
public class DefaultTraceGroupFields implements TraceGroupFields {

    private String endTime;
    private Long durationInNanos;
    private Integer statusCode;

    // required for serialization
    DefaultTraceGroupFields() {}

    private DefaultTraceGroupFields(final Builder builder) {

        checkNotNull(builder.durationInNanos, "durationInNanos cannot be null");
        checkNotNull(builder.statusCode, "statusCode cannot be null");
        checkNotNull(builder.endTime, "endTime cannot be null");
        checkArgument(!builder.endTime.isEmpty(), "endTime cannot be an empty string");

        this.endTime = builder.endTime;
        this.durationInNanos = builder.durationInNanos;
        this.statusCode = builder.statusCode;
    }

    @Override
    public String getEndTime() {
        return endTime;
    }

    @Override
    public Long getDurationInNanos() {
        return durationInNanos;
    }

    @Override
    public Integer getStatusCode() {
        return statusCode;
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for creating {@link DefaultTraceGroupFields}
     * @since 1.2
     */
    public static class Builder {

        private String endTime;
        private Long durationInNanos;
        private Integer statusCode;

        public Builder withEndTime(final String endTime) {
            this.endTime = endTime;
            return this;
        }

        public Builder withDurationInNanos(final Long durationInNanos) {
            this.durationInNanos = durationInNanos;
            return this;
        }

        public Builder withStatusCode(final Integer statusCode) {
            this.statusCode = statusCode;
            return this;
        }

        /**
         * Returns a newly created {@link DefaultTraceGroupFields}.
         * @return a DefaultTraceGroupFields
         * @since 1.2
         */
        public DefaultTraceGroupFields build() {
            return new DefaultTraceGroupFields(this);
        }
    }
}