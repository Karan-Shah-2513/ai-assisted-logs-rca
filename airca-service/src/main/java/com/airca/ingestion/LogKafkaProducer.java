package com.airca.ingestion;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Service;

@Service
public class LogKafkaProducer {

    private static final Logger LOGGER = LoggerFactory.getLogger(LogKafkaProducer.class);

    private final KafkaTemplate<String, LogMessage> kafkaTemplate;
    private final String topic;
    private final Duration sendTimeout;

    public LogKafkaProducer(
            KafkaTemplate<String, LogMessage> kafkaTemplate,
            @Value("${airca.ingestion.topic}") String topic,
            @Value("${airca.ingestion.send-timeout}") Duration sendTimeout) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic;
        this.sendTimeout = sendTimeout;
    }

    public void publish(UUID tenantId, String serviceName, String stackTrace, String correlationId) {
        LogMessage message = new LogMessage(
                tenantId, serviceName, stackTrace, correlationId, Instant.now());
        try {
            SendResult<String, LogMessage> result = kafkaTemplate
                    .send(topic, tenantId + ":" + correlationId, message)
                    .get(sendTimeout.toMillis(), TimeUnit.MILLISECONDS);
            LOGGER.debug("Queued log event {} at Kafka offset {}",
                    correlationId, result.getRecordMetadata().offset());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while publishing log event", exception);
        } catch (ExecutionException | TimeoutException exception) {
            throw new IllegalStateException("Unable to publish log event to Kafka", exception);
        }
    }
}
