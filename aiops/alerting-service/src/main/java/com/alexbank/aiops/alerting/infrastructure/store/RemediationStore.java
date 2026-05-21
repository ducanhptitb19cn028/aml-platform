package com.yourbank.aiops.alerting.infrastructure.store;

import com.yourbank.aiops.alerting.domain.RemediationRecord;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.List;

@Component
public class RemediationStore {

    private static final int MAX_SIZE = 100;

    private final Deque<RemediationRecord> store = new ArrayDeque<>(MAX_SIZE);

    public synchronized void add(RemediationRecord record) {
        if (store.size() >= MAX_SIZE) {
            store.pollFirst();
        }
        store.addLast(record);
    }

    public synchronized List<RemediationRecord> getRecent(int limit) {
        List<RemediationRecord> all = new ArrayList<>(store);
        int from = Math.max(0, all.size() - limit);
        List<RemediationRecord> slice = new ArrayList<>(all.subList(from, all.size()));
        Collections.reverse(slice);
        return slice;
    }
}
