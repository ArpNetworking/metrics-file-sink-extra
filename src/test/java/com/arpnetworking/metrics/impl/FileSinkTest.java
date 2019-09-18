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

import com.arpnetworking.metrics.Quantity;
import com.arpnetworking.metrics.Sink;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.fge.jackson.JacksonUtils;
import com.github.fge.jackson.JsonLoader;
import com.github.fge.jsonschema.core.exceptions.ProcessingException;
import com.github.fge.jsonschema.core.report.ProcessingReport;
import com.github.fge.jsonschema.main.JsonSchemaFactory;
import com.github.fge.jsonschema.main.JsonValidator;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import java.io.File;
import java.io.IOException;
import java.net.URL;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Scanner;
import java.util.UUID;

/**
 * Tests for {@link TsdMetrics}.
 *
 * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
 */
public class FileSinkTest {

    @Test
    public void testObjectMapperIOException() throws IOException {
        final org.slf4j.Logger logger = createSlf4jLoggerMock();
        final ObjectMapper objectMapper = Mockito.spy(new ObjectMapper());
        final Sink sink = new FileSink(
                new FileSink.Builder()
                        .setDirectory(createDirectory("./target/FileSinkTest"))
                        .setName("testObjectMapperIOException-Query"),
                objectMapper,
                logger);

        Mockito.doThrow(new JsonMappingException(Mockito.mock(JsonParser.class), "JsonMappingException"))
                .when(objectMapper)
                .writeValueAsString(Mockito.any());
        recordEmpty(sink);
        Mockito.verify(logger).warn(
                Mockito.any(String.class),
                Mockito.any(Throwable.class));
    }

    @Test
    public void testEmptySerialization() throws IOException, InterruptedException {
        final File actualFile = new File("./target/FileSinkTest/testEmptySerialization-Query.log");
        Files.deleteIfExists(actualFile.toPath());
        final Sink sink = new FileSink.Builder()
                .setDirectory(createDirectory("./target/FileSinkTest"))
                .setName("testEmptySerialization-Query")
                .setImmediateFlush(Boolean.TRUE)
                .setAsync(false)
                .build();

        sink.record(new TsdEvent(
                ANNOTATIONS,
                TEST_EMPTY_SERIALIZATION_TIMERS,
                TEST_EMPTY_SERIALIZATION_COUNTERS,
                TEST_EMPTY_SERIALIZATION_GAUGES,
                Collections.emptyMap()));

        // TODO(vkoskela): Add protected option to disable async [MAI-181].
        Thread.sleep(100);

        final String actualOriginalJson = fileToString(actualFile);
        assertMatchesJsonSchema(actualOriginalJson);
        final String actualComparableJson = actualOriginalJson
                .replaceAll("\"_host\":\"[^\"]*\"", "\"_host\":\"<HOST>\"")
                .replaceAll("\"_id\":\"[^\"]*\"", "\"_id\":\"<ID>\"");
        final JsonNode actual = OBJECT_MAPPER.readTree(actualComparableJson);
        final JsonNode expected = OBJECT_MAPPER.readTree(EXPECTED_EMPTY_METRICS_JSON);

        Assert.assertEquals(
                "expectedJson=" + OBJECT_MAPPER.writeValueAsString(expected)
                        + " vs actualJson=" + OBJECT_MAPPER.writeValueAsString(actual),
                expected,
                actual);
    }

    @Test
    public void testSerialization() throws IOException, InterruptedException {
        final File actualFile = new File("./target/FileSinkTest/testSerialization-Query.log");
        Files.deleteIfExists(actualFile.toPath());
        final Sink sink = new FileSink.Builder()
                .setDirectory(createDirectory("./target/FileSinkTest"))
                .setName("testSerialization-Query")
                .setImmediateFlush(Boolean.TRUE)
                .setAsync(false)
                .build();

        final Map<String, String> annotations = new LinkedHashMap<>(ANNOTATIONS);
        annotations.put("foo", "bar");
        sink.record(new TsdEvent(
                annotations,
                TEST_SERIALIZATION_TIMERS,
                TEST_SERIALIZATION_COUNTERS,
                TEST_SERIALIZATION_GAUGES,
                Collections.emptyMap()));

        // TODO(vkoskela): Add protected option to disable async [MAI-181].
        Thread.sleep(100);

        final String actualOriginalJson = fileToString(actualFile);
        assertMatchesJsonSchema(actualOriginalJson);
        final String actualComparableJson = actualOriginalJson
                .replaceAll("\"_host\":\"[^\"]*\"", "\"_host\":\"<HOST>\"")
                .replaceAll("\"_id\":\"[^\"]*\"", "\"_id\":\"<ID>\"");
        final JsonNode actual = OBJECT_MAPPER.readTree(actualComparableJson);
        final JsonNode expected = OBJECT_MAPPER.readTree(EXPECTED_METRICS_JSON);

        Assert.assertEquals(
                "expectedJson=" + OBJECT_MAPPER.writeValueAsString(expected)
                        + " vs actualJson=" + OBJECT_MAPPER.writeValueAsString(actual),
                expected,
                actual);
    }

    private static Map<String, List<Quantity>> createQuantityMap(final Object... arguments) {
        // CHECKSTYLE.OFF: IllegalInstantiation - No Guava
        final Map<String, List<Quantity>> map = new HashMap<>();
        // CHECKSTYLE.ON: IllegalInstantiation
        List<Quantity> samples = null;
        for (final Object argument : arguments) {
            if (argument instanceof String) {
                samples = new ArrayList<>();
                map.put((String) argument, samples);
            } else if (argument instanceof Quantity) {
                assert samples != null : "first argument must be metric name";
                samples.add((Quantity) argument);
            } else {
                assert false : "unsupported argument type: " + argument.getClass();
            }
        }
        return map;
    }

    private void recordEmpty(final Sink sink) {
        sink.record(new TsdEvent(
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap(),
                Collections.emptyMap()));
    }

    private org.slf4j.Logger createSlf4jLoggerMock() {
        return Mockito.mock(org.slf4j.Logger.class);
    }

    private void assertMatchesJsonSchema(final String json) {
        try {
            final JsonNode jsonNode = JsonLoader.fromString(json);
            final ProcessingReport report = VALIDATOR.validate(STENO_SCHEMA, jsonNode);
            Assert.assertTrue(report.toString(), report.isSuccess());
        } catch (final IOException | ProcessingException e) {
            Assert.fail("Failed with exception: " + e);
        }
    }

    private String fileToString(final File file) {
        try {
            return new Scanner(file, "UTF-8").useDelimiter("\\Z").next();
        } catch (final IOException ioe) {
            throw new RuntimeException(ioe);
        }
    }

    private static File createDirectory(final String path) throws IOException {
        final File directory = new File(path);
        Files.createDirectories(directory.toPath());
        return directory;
    }

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private static final JsonValidator VALIDATOR = JsonSchemaFactory.byDefault().getValidator();
    private static final JsonNode STENO_SCHEMA;

    private static final Map<String, String> ANNOTATIONS = new LinkedHashMap<>();
    private static final Map<String, List<Quantity>> TEST_EMPTY_SERIALIZATION_TIMERS = createQuantityMap();
    private static final Map<String, List<Quantity>> TEST_EMPTY_SERIALIZATION_COUNTERS = createQuantityMap();
    private static final Map<String, List<Quantity>> TEST_EMPTY_SERIALIZATION_GAUGES = createQuantityMap();

    private static final String EXPECTED_EMPTY_METRICS_JSON = "{"
            + "  \"version\":\"2f\","
            + "  \"annotations\":{"
            + "    \"_id\":\"<ID>\","
            + "    \"_start\":\"1997-07-16T19:20:30Z\","
            + "    \"_end\":\"1997-07-16T19:20:31Z\","
            + "    \"_service\":\"MyService\","
            + "    \"_cluster\":\"MyCluster\","
            + "    \"_host\":\"<HOST>\""
            + "  }"
            + "}";

    private static final Map<String, List<Quantity>> TEST_SERIALIZATION_TIMERS = createQuantityMap(
            "timerA",
            "timerB",
            TsdQuantity.newInstance(1L),
            "timerG",
            TsdQuantity.newInstance(9L),
            TsdQuantity.newInstance(10L),
            "timerI",
            TsdQuantity.newInstance(1.12),
            "timerN",
            TsdQuantity.newInstance(9.12),
            TsdQuantity.newInstance(10.12));

    private static final Map<String, List<Quantity>> TEST_SERIALIZATION_COUNTERS = createQuantityMap(
            "counterA",
            "counterB",
            TsdQuantity.newInstance(11L),
            "counterG",
            TsdQuantity.newInstance(19L),
            TsdQuantity.newInstance(110L),
            "counterI",
            TsdQuantity.newInstance(11.12),
            "counterN",
            TsdQuantity.newInstance(19.12),
            TsdQuantity.newInstance(110.12));


    private static final Map<String, List<Quantity>> TEST_SERIALIZATION_GAUGES = createQuantityMap(
            "gaugeA",
            "gaugeB",
            TsdQuantity.newInstance(21L),
            "gaugeG",
            TsdQuantity.newInstance(29L),
            TsdQuantity.newInstance(210L),
            "gaugeI",
            TsdQuantity.newInstance(21.12),
            "gaugeN",
            TsdQuantity.newInstance(29.12),
            TsdQuantity.newInstance(210.12));

    // CHECKSTYLE.OFF: LineLengthCheck - One value per line.
    private static final String EXPECTED_METRICS_JSON = "{"
            + "  \"version\":\"2f\","
            + "  \"annotations\":{"
            + "    \"_id\":\"<ID>\","
            + "    \"_start\":\"1997-07-16T19:20:30Z\","
            + "    \"_end\":\"1997-07-16T19:20:31Z\","
            + "    \"_service\":\"MyService\","
            + "    \"_cluster\":\"MyCluster\","
            + "    \"_host\":\"<HOST>\","
            + "    \"foo\":\"bar\""
            + "  },"
            + "  \"counters\":{"
            + "    \"counterA\":{\"values\":[]},"
            + "    \"counterB\":{\"values\":[{\"value\":11}]},"
            + "    \"counterG\":{\"values\":[{\"value\":19},{\"value\":110}]},"
            + "    \"counterI\":{\"values\":[{\"value\":11.12}]},"
            + "    \"counterN\":{\"values\":[{\"value\":19.12},{\"value\":110.12}]}"
            + "  },"
            + "    \"gauges\":{"
            + "    \"gaugeA\":{\"values\":[]},"
            + "    \"gaugeB\":{\"values\":[{\"value\":21}]},"
            + "    \"gaugeG\":{\"values\":[{\"value\":29},{\"value\":210}]},"
            + "    \"gaugeI\":{\"values\":[{\"value\":21.12}]},"
            + "    \"gaugeN\":{\"values\":[{\"value\":29.12},{\"value\":210.12}]}"
            + "  },"
            + "  \"timers\":{"
            + "    \"timerA\":{\"values\":[]},"
            + "    \"timerB\":{\"values\":[{\"value\":1}]},"
            + "    \"timerG\":{\"values\":[{\"value\":9},{\"value\":10}]},"
            + "    \"timerI\":{\"values\":[{\"value\":1.12}]},"
            + "    \"timerN\":{\"values\":[{\"value\":9.12},{\"value\":10.12}]}"
            + "  }"
            + "}";
    // CHECKSTYLE.ON: LineLengthCheck

    private static final String SCHEMA_FILE_NAME = "query-log-schema-2f.json";

    static {
        JsonNode jsonNode;
        try {
            // Attempt to load the cached copy
            jsonNode = JsonLoader.fromPath("./target/" + SCHEMA_FILE_NAME);
        } catch (final IOException e1) {
            try {
                // Download from the source repository
                jsonNode = JsonLoader.fromURL(
                        new URL("https://raw.githubusercontent.com/ArpNetworking/metrics-client-doc/master/schema/" + SCHEMA_FILE_NAME));

                // Cache the schema file
                Files.write(
                        Paths.get("./target/" + SCHEMA_FILE_NAME),
                        JacksonUtils.prettyPrint(jsonNode).getBytes(Charset.forName("UTF-8")));
            } catch (final IOException e2) {
                throw new RuntimeException(e2);
            }
        }
        STENO_SCHEMA = jsonNode;

        ANNOTATIONS.put("_start", "1997-07-16T19:20:30Z");
        ANNOTATIONS.put("_end", "1997-07-16T19:20:31Z");
        ANNOTATIONS.put("_id", UUID.randomUUID().toString());
        ANNOTATIONS.put("_host", "<HOST>");
        ANNOTATIONS.put("_service", "MyService");
        ANNOTATIONS.put("_cluster", "MyCluster");
    }
}
