package com.yourbank.aiops.alerting.infrastructure.store;

import com.yourbank.aiops.alerting.domain.IncidentOutcome;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

@Component
public class OutcomeStore {

    private static final int MAX_SIZE = 200;

    private final Deque<IncidentOutcome> store = new ArrayDeque<>(MAX_SIZE);

    public synchronized void add(IncidentOutcome outcome) {
        if (store.size() >= MAX_SIZE) {
            store.pollFirst();
        }
        store.addLast(outcome);
    }

    public synchronized List<IncidentOutcome> getAll() {
        return Collections.unmodifiableList(new ArrayList<>(store));
    }
}
