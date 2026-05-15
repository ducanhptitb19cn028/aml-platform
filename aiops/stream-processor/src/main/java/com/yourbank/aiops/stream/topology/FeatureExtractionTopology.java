package com.yourbank.aiops.stream.topology;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.yourbank.aiops.stream.model.FeatureRecord;
import com.yourbank.aiops.stream.model.MetricSignal;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serdes;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.streams.KeyValue;
import org.apache.kafka.streams.StreamsBuilder;
import org.apache.kafka.streams.kstream.*;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.time.Instant;

@Slf4j
@Configuration
public class FeatureExtractionTopology {

    private static final String TOPIC_IN  = "aiops.telemetry.metrics";
    private static final String TOPIC_OUT = "aiops.features";
    private static final Duration WINDOW_SIZE = Duration.ofSeconds(60);

    /**
     * Aggregation accumulator — not a record so we can mutate fields during windowed aggregation.
     */
    static final class MetricAgg {
        int    count      = 0;
        double sum        = 0.0;
        double max        = Double.MIN_VALUE;
        double firstValue = Double.NaN;
        double lastValue  = Double.NaN;
        String service    = "";
        String metricName = "";

        void add(double value, String svc, String metric) {
            if (count == 0) {
                firstValue = value;
                service    = svc;
                metricName = metric;
            }
            count++;
            sum   += value;
            if (value > max) max = value;
            lastValue = value;
        }
    }

    @Bean
    public KStream<String, FeatureRecord> featureExtractionStream(StreamsBuilder streamsBuilder) {

        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
                .setVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY);

        Serde<MetricSignal> metricSerde = jsonSerde(mapper, MetricSignal.class);
        Serde<MetricAgg>    aggSerde    = jsonSerde(mapper, MetricAgg.class);
        Serde<FeatureRecord> featureSerde = jsonSerde(mapper, FeatureRecord.class);

        KStream<String, MetricSignal> rawStream = streamsBuilder.stream(
                TOPIC_IN,
                Consumed.with(Serdes.String(), metricSerde)
        );

        KStream<String, FeatureRecord> featureStream = rawStream
                // Re-key to service.metricName for grouping
                .selectKey((k, v) -> (v.service() != null ? v.service() : "unknown")
                        + "." + (v.metricName() != null ? v.metricName() : "unknown"))
                .groupByKey(Grouped.with(Serdes.String(), metricSerde))
                .windowedBy(TimeWindows.ofSizeWithNoGrace(WINDOW_SIZE))
                .aggregate(
                        MetricAgg::new,
                        (key, value, agg) -> {
                            agg.add(value.value(),
                                    value.service()    != null ? value.service()    : "unknown",
                                    value.metricName() != null ? value.metricName() : "unknown");
                            return agg;
                        },
                        Materialized.with(Serdes.String(), aggSerde)
                )
                .toStream()
                .map((windowedKey, agg) -> {
                    if (agg == null || agg.count == 0) {
                        return KeyValue.pair(windowedKey.key(), (FeatureRecord) null);
                    }

                    double avg          = agg.sum / agg.count;
                    double rateOfChange = Double.isNaN(agg.firstValue) ? 0.0
                            : (agg.lastValue - agg.firstValue);

                    Instant windowStart = Instant.ofEpochMilli(windowedKey.window().start());
                    Instant windowEnd   = Instant.ofEpochMilli(windowedKey.window().end());

                    String windowId = agg.service + "." + agg.metricName
                            + "@" + windowStart.toEpochMilli();

                    FeatureRecord feature = new FeatureRecord(
                            windowId,
                            agg.service,
                            windowStart,
                            windowEnd,
                            avg,
                            agg.max == Double.MIN_VALUE ? avg : agg.max,
                            rateOfChange,
                            agg.metricName,
                            agg.count
                    );
                    log.debug("Emitting FeatureRecord windowId={} service={} metric={} avg={}",
                            windowId, agg.service, agg.metricName, avg);
                    return KeyValue.pair(agg.service, feature);
                })
                .filter((k, v) -> v != null);

        featureStream.to(TOPIC_OUT, Produced.with(Serdes.String(), featureSerde));
        return featureStream;
    }

    /**
     * Generic JSON Serde backed by Jackson.
     */
    private <T> Serde<T> jsonSerde(ObjectMapper mapper, Class<T> clazz) {
        Serializer<T> serializer = (topic, data) -> {
            if (data == null) return null;
            try {
                return mapper.writeValueAsBytes(data);
            } catch (Exception e) {
                log.error("Serialization error for {}: {}", clazz.getSimpleName(), e.getMessage());
                return null;
            }
        };

        Deserializer<T> deserializer = (topic, bytes) -> {
            if (bytes == null) return null;
            try {
                return mapper.readValue(bytes, clazz);
            } catch (Exception e) {
                log.error("Deserialization error for {}: {}", clazz.getSimpleName(), e.getMessage());
                return null;
            }
        };

        return Serdes.serdeFrom(serializer, deserializer);
    }
}
