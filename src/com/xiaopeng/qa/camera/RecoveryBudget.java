package com.xiaopeng.qa.camera;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/** One explicit enable episode. Successful callbacks and time never reset it. */
final class RecoveryBudget {
    enum Kind { OPEN_RETRY, CONFIGURE_RETRY, OUTPUT_REPLACE, RELAY_REBUILD }
    private int total, opens, configurations, relays;
    private final Set<String> operations = new HashSet<>();
    private final Map<String, long[]> outputs = new HashMap<>();

    synchronized boolean admit(String operation, Kind kind, String key, long now) {
        if (operations.contains(operation) || total >= 5) return false;
        if (kind == Kind.OPEN_RETRY && opens >= 3) return false;
        if (kind == Kind.CONFIGURE_RETRY && configurations >= 2) return false;
        if (kind == Kind.RELAY_REBUILD && relays >= 1) return false;
        long[] output = outputs.get(key);
        if (kind == Kind.OUTPUT_REPLACE && output != null
                && output[1] >= 0L && now - output[0] < 30000L) return false;
        operations.add(operation);
        total++;
        switch (kind) {
            case OPEN_RETRY: opens++; break;
            case CONFIGURE_RETRY: configurations++; break;
            case RELAY_REBUILD: relays++; break;
            case OUTPUT_REPLACE:
                // Two most recent admissions; rolling window only, shared total never resets.
                if (output == null) outputs.put(key, new long[]{now, -1L});
                else if (output[1] < 0L) outputs.put(key, new long[]{output[0], now});
                else outputs.put(key, new long[]{output[1], now});
                break;
        }
        return true;
    }

    synchronized boolean contains(String operation) { return operations.contains(operation); }

    synchronized int used() { return total; }
}
