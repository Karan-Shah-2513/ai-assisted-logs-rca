package com.airca.ingestion;

import com.airca.multitenancy.TenantContext;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
public class LogKafkaConsumer {

    private final LogIngestionProcessor processor;

    public LogKafkaConsumer(LogIngestionProcessor processor) {
        this.processor = processor;
    }

    @KafkaListener(topics = "${airca.ingestion.topic}")
    public void consume(LogMessage message) {
        UUID tenantId = message.tenantId();
        TenantContext.setTenantId(tenantId);
        try {
            processor.process(message);
        } finally {
            TenantContext.clear();
        }
    }
}
