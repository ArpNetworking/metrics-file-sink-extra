/*
 * Copyright 2015 Groupon.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.arpnetworking.metrics.impl;

import ch.qos.logback.classic.encoder.PatternLayoutEncoder;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.encoder.Encoder;
import com.arpnetworking.metrics.Event;
import com.arpnetworking.metrics.Quantity;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.databind.JsonSerializer;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializerProvider;
import com.fasterxml.jackson.databind.module.SimpleModule;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

/**
 * Implementation of {@link com.arpnetworking.metrics.Sink} for the query log file.
 *
 * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
 */
public class FileSink extends BaseFileSink {

    @Override
    public void record(final Event event) {
        try {
            getMetricsLogger().info(_objectMapper.writeValueAsString(event));
        } catch (final IOException e) {
            _logger.warn("Exception recording event", e);
        }
    }

    private static Encoder<ILoggingEvent> createEncoder() {
        final PatternLayoutEncoder encoder = new PatternLayoutEncoder();
        encoder.setPattern("%msg%n");
        return encoder;
    }

    /**
     * Protected constructor.
     *
     * @param builder Instance of {@link Builder}.
     */
    protected FileSink(final Builder builder) {
        this(builder, OBJECT_MAPPER, LOGGER);
    }

    // NOTE: Package private for testing
    /* package private */ FileSink(final Builder builder, final ObjectMapper objectMapper, final org.slf4j.Logger logger) {
        super(builder, createEncoder());
        _objectMapper = objectMapper;
        _logger = logger;
    }

    private final ObjectMapper _objectMapper;
    private final org.slf4j.Logger _logger;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(FileSink.class);

    static {
        final SimpleModule simpleModule = new SimpleModule("FileSink");
        simpleModule.addSerializer(Event.class, EventSerializer.newInstance());
        simpleModule.addSerializer(Quantity.class, QuantitySerializer.newInstance());
        OBJECT_MAPPER.setSerializationInclusion(JsonInclude.Include.ALWAYS);
        OBJECT_MAPPER.registerModule(simpleModule);
    }

    private static final class EventSerializer extends JsonSerializer<Event> {

        public static JsonSerializer<Event> newInstance() {
            return new EventSerializer();
        }

        @Override
        public void serialize(
                final Event event,
                final JsonGenerator jsonGenerator,
                final SerializerProvider provider)
                throws IOException {

            jsonGenerator.writeStartObject();
            jsonGenerator.writeStringField("version", VERSION);
            jsonGenerator.writeObjectField("annotations", event.getAnnotations());
            if (!event.getCounterSamples().isEmpty()) {
                jsonGenerator.writeObjectFieldStart("counters");
                serializeSamples(event.getCounterSamples(), jsonGenerator);
                jsonGenerator.writeEndObject();
            }
            if (!event.getGaugeSamples().isEmpty()) {
                jsonGenerator.writeObjectFieldStart("gauges");
                serializeSamples(event.getGaugeSamples(), jsonGenerator);
                jsonGenerator.writeEndObject();
            }
            if (!event.getTimerSamples().isEmpty()) {
                jsonGenerator.writeObjectFieldStart("timers");
                serializeSamples(event.getTimerSamples(), jsonGenerator);
                jsonGenerator.writeEndObject();
            }
            jsonGenerator.writeEndObject();
        }

        private void serializeSamples(
                final Map<String, ? extends Collection<? extends Quantity>> samples,
                final JsonGenerator jsonGenerator)
                throws IOException {
            for (final Map.Entry<String, ? extends Collection<? extends Quantity>> entry : samples.entrySet()) {
                jsonGenerator.writeObjectFieldStart(entry.getKey());
                jsonGenerator.writeObjectField("values", entry.getValue());
                jsonGenerator.writeEndObject();
            }
        }

        private EventSerializer() {}

        private static final String VERSION = "2f";
    }

    private static final class QuantitySerializer extends JsonSerializer<Quantity> {

        public static JsonSerializer<Quantity> newInstance() {
            return new QuantitySerializer();
        }

        @Override
        public void serialize(
                final Quantity value,
                final JsonGenerator jsonGenerator,
                final SerializerProvider provider)
                throws IOException {
            jsonGenerator.writeStartObject();
            jsonGenerator.writeObjectField("value", value.getValue());
            jsonGenerator.writeEndObject();
        }
    }

    /**
     * Builder for {@link FileSink}.
     *
     * This class is thread safe.
     *
     * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
     */
    public static class Builder extends BaseFileSink.Builder<FileSink, Builder> {

        @Override
        protected FileSink createSink() {
            return new FileSink(this);
        }

        @Override
        protected Builder self() {
            return this;
        }
    }
}
