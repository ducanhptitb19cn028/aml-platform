package com.yourbank.aiops.alerting.infrastructure.sse;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.CopyOnWriteArrayList;

@Slf4j
@Component
public class SseEmitterRegistry {

    private final CopyOnWriteArrayList<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ObjectMapper objectMapper;

    public SseEmitterRegistry(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public SseEmitter register() {
        SseEmitter emitter = new SseEmitter(0L); // no timeout — client reconnects on disconnect
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(()   -> emitters.remove(emitter));
        emitter.onError(e     -> emitters.remove(emitter));
        log.debug("SSE client connected — active={}", emitters.size());
        return emitter;
    }

    public void broadcast(String eventType, Object payload) {
        if (emitters.isEmpty()) return;
        String json;
        try {
            json = objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            log.warn("SSE serialize error: {}", e.getMessage());
            return;
        }
        emitters.forEach(emitter -> {
            try {
                emitter.send(SseEmitter.event().name(eventType).data(json));
            } catch (IOException ex) {
                emitters.remove(emitter);
            }
        });
        log.debug("SSE broadcast event={} to {} clients", eventType, emitters.size());
    }
}
