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

import ch.qos.logback.classic.AsyncAppender;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import ch.qos.logback.core.FileAppender;
import ch.qos.logback.core.encoder.Encoder;
import ch.qos.logback.core.rolling.RollingFileAppender;
import ch.qos.logback.core.rolling.RollingPolicy;
import ch.qos.logback.core.rolling.TimeBasedRollingPolicy;
import ch.qos.logback.core.util.FileSize;
import com.arpnetworking.logback.SizeAndRandomizedTimeBasedFNATP;
import com.arpnetworking.metrics.Sink;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import javax.annotation.Nullable;

/**
 * Base class for {@link Sink} implementations which write to files. This
 * implementation uses Logback as the underlying implementation to write events
 * to disk. It is designed not to interfere with Logback or SLF4J usage for
 * application logging.
 *
 * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
 */
/* package private */ abstract class BaseFileSink implements Sink {

    /**
     * Accessor for the {@link Logger} instance to write to.
     *
     * @return The {@link Logger} instance to write to.
     */
    protected Logger getMetricsLogger() {
        return _metricsLogger;
    }

    private TimeBasedRollingPolicy<ILoggingEvent> createRollingPolicy(
            final String extension,
            final String fileNameWithoutExtension,
            final int maxHistory,
            final String maxFileSizeAsString,
            final boolean compress) {

        final FileSize maxFileSize = FileSize.valueOf(maxFileSizeAsString);
        final FileSize totalSizeCap = FileSize.valueOf(String.valueOf(maxHistory * maxFileSize.getSize()));

        final SizeAndRandomizedTimeBasedFNATP<ILoggingEvent> triggeringPolicy = new SizeAndRandomizedTimeBasedFNATP<>();
        triggeringPolicy.setContext(_loggerContext);
        triggeringPolicy.setMaxOffsetInMillis(MAX_RANDOM_OFFSET_IN_MILLIS);
        triggeringPolicy.setMaxFileSize(maxFileSize);

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = new TimeBasedRollingPolicy<>();
        rollingPolicy.setTimeBasedFileNamingAndTriggeringPolicy(triggeringPolicy);
        rollingPolicy.setContext(_loggerContext);
        rollingPolicy.setMaxHistory(maxHistory);
        rollingPolicy.setTotalSizeCap(totalSizeCap);
        rollingPolicy.setCleanHistoryOnStart(true);
        if (compress) {
            rollingPolicy.setFileNamePattern(fileNameWithoutExtension + DATE_AND_INDEX_EXTENSION + extension + GZIP_EXTENSION);
        } else {
            rollingPolicy.setFileNamePattern(fileNameWithoutExtension + DATE_AND_INDEX_EXTENSION + extension);
        }

        return rollingPolicy;
    }

    private FileAppender<ILoggingEvent> createRollingAppender(
            final String fileName,
            final RollingPolicy rollingPolicy,
            final Encoder<ILoggingEvent> encoder,
            final boolean immediateFlush) {
        final RollingFileAppender<ILoggingEvent> rollingAppender = new RollingFileAppender<>();
        rollingAppender.setContext(_loggerContext);
        rollingAppender.setName("query-log");
        rollingAppender.setFile(fileName);
        rollingAppender.setAppend(true);
        rollingAppender.setRollingPolicy(rollingPolicy);
        rollingAppender.setEncoder(encoder);
        rollingAppender.setImmediateFlush(immediateFlush);
        return rollingAppender;
    }

    private Appender<ILoggingEvent> createAsyncAppender(
            final Appender<ILoggingEvent> appender,
            final int discardingThreshold,
            final int queueSize) {
        final AsyncAppender asyncAppender = new AsyncAppender();
        asyncAppender.setContext(_loggerContext);
        asyncAppender.setDiscardingThreshold(discardingThreshold);
        asyncAppender.setName("query-log-async");
        asyncAppender.setQueueSize(queueSize);
        asyncAppender.addAppender(appender);
        return asyncAppender;
    }

    /**
     * Protected constructor.
     *
     * @param builder Instance of {@link Builder}.
     */
    protected BaseFileSink(
            final Builder<? extends BaseFileSink, ? extends Builder<? extends Sink, ?>> builder,
            final Encoder<ILoggingEvent> encoder) {
        final StringBuilder fileNameBuilder = new StringBuilder(builder._directory.getPath());
        fileNameBuilder.append(File.separator);
        fileNameBuilder.append(builder._name);
        final String fileNameWithoutExtension = fileNameBuilder.toString();
        fileNameBuilder.append(builder._extension);
        final String fileName = fileNameBuilder.toString();

        _loggerContext = new LoggerContext();
        encoder.setContext(_loggerContext);

        final TimeBasedRollingPolicy<ILoggingEvent> rollingPolicy = createRollingPolicy(
                builder._extension,
                fileNameWithoutExtension,
                builder._maxHistory,
                builder._maxFileSize,
                builder._compress);
        final FileAppender<ILoggingEvent> rollingAppender = createRollingAppender(
                fileName,
                rollingPolicy,
                encoder,
                builder._immediateFlush);

        rollingPolicy.setParent(rollingAppender);
        rollingPolicy.start();
        encoder.start();
        rollingAppender.start();

        final Appender<ILoggingEvent> appender;
        if (builder._async) {
            appender = createAsyncAppender(
                    rollingAppender,
                    builder._dropWhenQueueFull ? builder._maxQueueSize : 0,
                    builder._maxQueueSize);
            appender.start();
        } else {
            appender = rollingAppender;
        }

        final Logger rootLogger = _loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
        rootLogger.setLevel(Level.INFO);
        rootLogger.addAppender(appender);

        Runtime.getRuntime().addShutdownHook(new ShutdownHookThread(_loggerContext));

        _metricsLogger = _loggerContext.getLogger(Logger.ROOT_LOGGER_NAME);
    }

    private final LoggerContext _loggerContext;
    private final Logger _metricsLogger;

    private static final int MAX_RANDOM_OFFSET_IN_MILLIS = 10 * 60 * 1000; // 10 minutes
    private static final String DATE_AND_INDEX_EXTENSION = ".%d{yyyy-MM-dd-HH}.%i";
    private static final String GZIP_EXTENSION = ".gz";

    private static final org.slf4j.Logger LOGGER = LoggerFactory.getLogger(BaseFileSink.class);

    // NOTE: Package private for testing
    /* package private */ static final class ShutdownHookThread extends Thread {

        /* package private */ ShutdownHookThread(final LoggerContext context) {
            _context = context;
        }

        @Override
        public void run() {
            _context.stop();
        }

        private final LoggerContext _context;
    }

    /**
     * Builder for {@link BaseFileSink}.
     *
     * This class is thread safe.
     *
     * @author Ville Koskela (ville dot koskela at inscopemetrics dot io)
     */
    public abstract static class Builder<T extends Sink, B extends Builder<? extends Sink, ?>> {

        /**
         * Create an instance of {@link Sink}.
         *
         * @return Instance of {@link Sink}.
         */
        public Sink build() {
            // Defaults
            applyDefaults();

            // Validate
            final List<String> failures = new ArrayList<>();
            validate(failures);

            // Fallback
            if (!failures.isEmpty()) {
                LOGGER.warn(String.format(
                        "Unable to construct %s, metrics disabled; failures=%s",
                        this.getClass().getEnclosingClass().getSimpleName(),
                        failures));
                return new WarningSink.Builder()
                        .setReasons(failures)
                        .build();
            }

            return createSink();
        }

        /**
         * Set the directory. Optional; default is the current working directory
         * of the application.
         *
         * @param value The value for directory.
         * @return This {@link Builder} instance.
         */
        public B setDirectory(@Nullable final File value) {
            _directory = value;
            return self();
        }

        /**
         * Set the file name without extension. Optional; default is "query".
         * The file name without extension cannot be empty.
         *
         * @param value The value for name.
         * @return This {@link Builder} instance.
         */
        public B setName(@Nullable final String value) {
            _name = value;
            return self();
        }

        /**
         * Set the file extension. Optional; default is ".log".
         *
         * @param value The value for extension.
         * @return This {@link Builder} instance.
         */
        public B setExtension(@Nullable final String value) {
            _extension = value;
            return self();
        }

        /**
         * Set the max history to retain. Optional; default is 24.
         *
         * @param value The value for max history.
         * @return This {@link Builder} instance.
         */
        public B setMaxHistory(@Nullable final Integer value) {
            _maxHistory = value;
            return self();
        }

        /**
         * Set the max file size. Accepted units are: "KB", "MB" and "GB". Optional; default is "100MB".
         *
         * @param value The value for max file size in megabytes.
         * @return This {@link Builder} instance.
         */
        public B setMaxFileSize(@Nullable final String value) {
            _maxFileSize = value;
            return self();
        }

        /**
         * Set whether files are compressed on roll. Optional; default is true.
         *
         * @param value Whether to compress on roll.
         * @return This {@link Builder} instance.
         */
        public B setCompress(@Nullable final Boolean value) {
            _compress = value;
            return self();
        }

        /**
         * Set whether entries are flushed immediately. Entries are still
         * written asynchronously unless async is disabled. Optional; default
         * is true.
         *
         * @param value Whether to flush immediately.
         * @return This {@link Builder} instance.
         */
        public B setImmediateFlush(@Nullable final Boolean value) {
            _immediateFlush = value;
            return self();
        }

        /**
         * Set whether files are written asynchronously. Optional; default is true.
         *
         * @param value Whether to write asynchronously.
         * @return This {@link Builder} instance.
         */
        public B setAsync(@Nullable final Boolean value) {
            _async = value;
            return self();
        }

        /**
         * Set whether to drop events when the queue is full. If events are not
         * dropped when the queue is full closing a {@link com.arpnetworking.metrics.Metrics}
         * instance will block on writing to this {@link Sink}. Optional;
         * default is false.
         *
         * @param value Whether to drop events when the queue is full.
         * @return This {@link Builder} instance.
         */
        public B setDropWhenQueueFull(@Nullable final Boolean value) {
            _dropWhenQueueFull = value;
            return self();
        }

        /**
         * Set maximum event queue size. Optional; default is 500.
         *
         * @param value The maximum event queue size.
         * @return This {@link Builder} instance.
         */
        public B setMaxQueueSize(@Nullable final Integer value) {
            _maxQueueSize = value;
            return self();
        }

        /**
         * Protected method allows child builder classes to add additional
         * defaulting behavior to fields.
         */
        protected void applyDefaults() {
            if (_directory == null) {
                _directory = DEFAULT_DIRECTORY;
                LOGGER.info(String.format("Defaulted null directory; directory=%s", _directory));
            }
            if (_name == null) {
                _name = DEFAULT_NAME;
                LOGGER.info(String.format("Defaulted null name; name=%s", _name));
            }
            if (_extension == null) {
                _extension = DEFAULT_EXTENSION;
                LOGGER.info(String.format("Defaulted null extension; extension=%s", _extension));
            }
            if (_maxHistory == null) {
                _maxHistory = DEFAULT_MAX_HISTORY;
                LOGGER.info(String.format("Defaulted null max history; maxHistory=%d", _maxHistory));
            }
            if (_maxFileSize == null) {
                _maxFileSize = DEFAULT_MAX_FILE_SIZE;
                LOGGER.info(String.format("Defaulted null max file size; maxFileSize=%s", _maxFileSize));
            }
            if (_compress == null) {
                _compress = DEFAULT_COMPRESS;
                LOGGER.info(String.format("Defaulted null compress; compress=%b", _compress));
            }
            if (_immediateFlush == null) {
                _immediateFlush = DEFAULT_IMMEDIATE_FLUSH;
                LOGGER.info(String.format("Defaulted null immediate flush; immediateFlush=%b", _immediateFlush));
            }
            if (_async == null) {
                _async = DEFAULT_ASYNC;
                LOGGER.info(String.format("Defaulted null async; async=%b", _async));
            }
            if (_dropWhenQueueFull == null) {
                _dropWhenQueueFull = DEFAULT_DROP_WHEN_QUEUE_FULL;
                LOGGER.info(String.format("Defaulted null drop when queue full; dropWhenQueueFull=%s", _dropWhenQueueFull));
            }
            if (_maxQueueSize == null) {
                _maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;
                LOGGER.info(String.format("Defaulted null max queue size; maxQueueSize=%d", _maxQueueSize));
            }
        }

        /**
         * Protected method allows child builder classes to add additional
         * validation to fields.
         *
         * @param failures List of validation failures.
         */
        protected void validate(final List<String> failures) {
            if (!_directory.isDirectory()) {
                failures.add(String.format("Path is not a directory; path=%s", _directory));
            }
            if (!_directory.exists()) {
                failures.add(String.format("Path does not exist; path=%s", _directory));
            }
        }

        /**
         * Protected method delegates construction of the actual {@link Sink}
         * instance to the concrete builder child class.
         *
         * @return Instance of {@link T}.
         */
        protected abstract T createSink();

        /**
         * Protected method returns this builder with the correct top-level type.
         *
         * @return This builder with the correct top-level type.
         */
        protected abstract B self();

        protected File _directory = DEFAULT_DIRECTORY;
        protected String _name = DEFAULT_NAME;
        protected String _extension = DEFAULT_EXTENSION;
        protected Integer _maxHistory = DEFAULT_MAX_HISTORY;
        protected String _maxFileSize = DEFAULT_MAX_FILE_SIZE;
        protected Boolean _compress = DEFAULT_COMPRESS;
        protected Boolean _immediateFlush = DEFAULT_IMMEDIATE_FLUSH;
        protected Boolean _async = DEFAULT_ASYNC;
        protected Boolean _dropWhenQueueFull = DEFAULT_DROP_WHEN_QUEUE_FULL;
        protected Integer _maxQueueSize = DEFAULT_MAX_QUEUE_SIZE;

        private static final File DEFAULT_DIRECTORY = new File("./");
        private static final String DEFAULT_NAME = "query";
        private static final String DEFAULT_EXTENSION = ".log";
        private static final Integer DEFAULT_MAX_HISTORY = 24;
        private static final String DEFAULT_MAX_FILE_SIZE = "100MB";
        private static final Boolean DEFAULT_COMPRESS = Boolean.TRUE;
        private static final Boolean DEFAULT_IMMEDIATE_FLUSH = Boolean.FALSE;
        private static final Boolean DEFAULT_ASYNC = Boolean.TRUE;
        private static final Boolean DEFAULT_DROP_WHEN_QUEUE_FULL = Boolean.FALSE;
        private static final Integer DEFAULT_MAX_QUEUE_SIZE = 500;
    }
}
