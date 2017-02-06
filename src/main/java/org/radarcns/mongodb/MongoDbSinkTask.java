/*
 *  Copyright 2016 Kings College London and The Hyve
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.radarcns.mongodb;

import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.AbstractConfig;
import org.apache.kafka.connect.errors.IllegalWorkerStateException;
import org.apache.kafka.connect.sink.SinkTask;
import org.apache.kafka.connect.sink.SinkRecord;
import org.radarcns.serialization.RecordConverterFactory;
import org.radarcns.util.Monitor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collection;
import java.util.Map;
import java.util.Timer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import static org.radarcns.mongodb.MongoDbSinkConnector.BUFFER_CAPACITY;
import static org.radarcns.mongodb.MongoDbSinkConnector.RECORD_CONVERTER;

/**
 * Task to handle data coming from Kafka and send it to MongoDB.
 *
 * It uses a buffer and a separate MongoDbWriter thread to achieve asynchronous writes. The buffer
 * is of fixed size (defined by {@link MongoDbSinkConnector#BUFFER_CAPACITY}) so if the MongoDB
 * connection is slower than data is being put from Kafka, the buffer will fill up. The put
 * operation will then at some point timeout.
 */
public class MongoDbSinkTask extends SinkTask {
    private static final Logger log = LoggerFactory.getLogger(MongoDbSinkTask.class);
    private final Monitor monitor;

    private BlockingQueue<SinkRecord> buffer;
    private MongoDbWriter writer;
    private Thread writerThread;
    private Timer timer;

    public MongoDbSinkTask() {
        monitor = new Monitor(log, "have been processed");
    }

    @Override
    public String version() {
        return new MongoDbSinkConnector().version();
    }

    @Override
    public void start(Map<String, String> props) {
        timer = new Timer();
        timer.schedule(monitor, 0, 30_000);

        AbstractConfig config = new AbstractConfig(
                new MongoDbSinkConnector().config().parse(props));

        buffer = new ArrayBlockingQueue<>(config.getInt(BUFFER_CAPACITY));

        RecordConverterFactory converterFactory;
        try {
            converterFactory = (RecordConverterFactory)config.getClass(RECORD_CONVERTER)
                    .newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassCastException e) {
            throw new IllegalWorkerStateException("Got illegal RecordConverterClass", e);
        }
        writer = createMongoDbWriter(config, buffer, converterFactory, timer);
        writerThread = new Thread(writer, "MongDB-writer");
        writerThread.start();
    }

    // public helper function for easier mocking
    public MongoDbWriter createMongoDbWriter(AbstractConfig config,
                                      BlockingQueue<SinkRecord> buffer,
                                      RecordConverterFactory converterFactory, Timer timer) {
        MongoWrapper mongoHelper = new MongoWrapper(config, null);

        return new MongoDbWriter(mongoHelper, buffer, converterFactory, timer);
    }

    @Override
    public void put(Collection<SinkRecord> sinkRecords) {
        log.debug("Init put");
        long startTime = System.nanoTime();

        for (SinkRecord record : sinkRecords) {
            buffer.add(record);
            monitor.increment();

            log.debug("{} --> {}", new TopicPartition(record.topic(), record.kafkaPartition()).toString(), record.kafkaOffset());
        }

        long endTime = System.nanoTime();
        log.debug("[PUT] Time-laps: {}nsec", endTime - startTime);
        log.debug("End put");
    }

    @Override
    public void flush(Map<TopicPartition, OffsetAndMetadata> offsets) {
        log.debug("Init flush");
        long startTime = System.nanoTime();

        writer.flush(offsets);

        long endTime = System.nanoTime();
        log.info("[FLUSH] Time-laps: {}nsec", endTime - startTime);
        log.debug("End flush");
    }

    @Override
    public void stop() {
        writer.close();
        writerThread.interrupt();
        timer.cancel();
        try {
            writerThread.join(30_000L);
        } catch (InterruptedException ex) {
            log.info("Failed to wait for writer thread to finish.", ex);
        }
    }
}