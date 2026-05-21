package com.alexbank.aiops.alerting.infrastructure.store;

import com.alexbank.aiops.alerting.domain.Incident;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

@Component
public class IncidentStore {

    private static final int MAX_SIZE = 100;

    private final Deque<Incident> store = new ArrayDeque<>(MAX_SIZE);

    public synchronized void add(Incident incident) {
        if (store.size() >= MAX_SIZE) {
            store.pollFirst();
        }
        store.addLast(incident);
    }

    public synchronized List<Incident> getRecent(int limit) {
        List<Incident> all = new ArrayList<>(store);
        int from = Math.max(0, all.size() - limit);
        // Return newest first
        List<Incident> slice = new ArrayList<>(all.subList(from, all.size()));
        java.util.Collections.reverse(slice);
        return slice;
    }
}
