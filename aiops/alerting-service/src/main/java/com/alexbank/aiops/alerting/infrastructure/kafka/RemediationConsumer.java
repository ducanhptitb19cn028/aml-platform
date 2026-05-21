package com.alexbank.aiops.alerting.infrastructure.kafka;

import com.alexbank.aiops.alerting.domain.RemediationRecord;
import com.alexbank.aiops.alerting.infrastructure.store.RemediationStore;
import com.alexbank.aiops.alerting.infrastructure.sse.SseEmitterRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@RequiredArgsConstructor
public class RemediationConsumer {

    private final RemediationStore  remediationStore;
    private final SseEmitterRegistry sseRegistry;

    @KafkaListener(topics = "aiops.actions", groupId = "aiops-alerting-service",
            properties = "spring.json.value.default.type=com.alexbank.aiops.alerting.domain.RemediationRecord")
    public void consume(RemediationRecord record) {
        if (record == null) return;
        remediationStore.add(record);
        sseRegistry.broadcast("remediation", record);
        log.debug("Stored remediation recordId={} action={} status={}",
                record.recordId(), record.action(), record.status());
    }
}
