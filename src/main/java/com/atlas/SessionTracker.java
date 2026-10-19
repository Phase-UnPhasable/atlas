package com.atlas;

import java.util.HashMap;
import java.util.Map;

/** Quantity changes at fixed per-session prices; unchanged holdings never create session profit/loss. */
final class SessionTracker
{
    private final long start;
    private final Map<Integer, Long> prices = new HashMap<>();
    private final Map<Integer, Long> changes = new HashMap<>();
    private Wealth.Snapshot previous;
    private boolean bankObserved, estimated;

    SessionTracker(long start) { this.start = start; }

    void markEstimated() { estimated = true; }

    void observe(Wealth.Snapshot snapshot, boolean observedBank)
    {
        observe(snapshot, observedBank, false);
    }

    void observe(Wealth.Snapshot snapshot, boolean observedBank, boolean reconcile)
    {
        observe(snapshot, observedBank, reconcile, java.util.Collections.emptyMap());
    }

    /** Neutralize observed rune transfers into/out of the Soul Bearer, not rune consumption. */
    void observe(Wealth.Snapshot snapshot, boolean observedBank, boolean reconcile, Map<Integer, Long> transfers)
    {
        // Use the first available positive quote for each item and never reprice booked changes.
        snapshot.items.values().forEach(e -> { if (e.price > 0) { prices.putIfAbsent(e.id, e.price); } });
        estimated |= !observedBank || snapshot.bankTime == 0;
        if (previous != null && !reconcile && !(observedBank && !bankObserved))
        {
            previous.items.values().forEach(old -> changes.merge(old.id, -old.quantity(), Long::sum));
            snapshot.items.values().forEach(item -> changes.merge(item.id, item.quantity(), Long::sum));
            transfers.forEach((id, quantity) -> changes.merge(id, quantity, Long::sum));
            changes.values().removeIf(quantity -> quantity == 0);
        }
        // The first real bank observation reconciles unknown/stale bank data without calling it loot/loss.
        bankObserved |= observedBank;
        previous = snapshot;
    }

    /** Charges spent from a preloaded item do not appear as loose-rune container changes. */
    void spend(int id, long quantity, long price)
    {
        if (quantity <= 0) { return; }
        if (price > 0) { prices.putIfAbsent(id, price); }
        changes.merge(id, -quantity, Long::sum);
    }

    Wealth.Session current()
    {
        if (previous == null) { return null; }
        long value = changes.entrySet().stream().mapToLong(e -> e.getValue() * prices.getOrDefault(e.getKey(), 0L)).sum();
        return new Wealth.Session(start, Math.max(start, previous.time), value, estimated, false);
    }
}
