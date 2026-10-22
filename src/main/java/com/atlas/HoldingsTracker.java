package com.atlas;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.IntPredicate;
import java.util.function.IntUnaryOperator;
import net.runelite.api.Item;

/** Client-thread state: retain the last observed bank when its live container disappears. */
final class HoldingsTracker
{
    private final IntUnaryOperator canonicalize;
    private final IntPredicate isPlaceholder;
    private Map<Integer, Long> bank;
    private final Map<Integer, Long> remoteBank = new LinkedHashMap<>();
    private long bankTime;

    HoldingsTracker(IntUnaryOperator canonicalize, IntPredicate isPlaceholder)
    {
        this.canonicalize = canonicalize;
        this.isPlaceholder = isPlaceholder;
    }

    void reset()
    {
        bank = null;
        remoteBank.clear();
        bankTime = 0;
    }

    void observeBank(Item[] items, long time)
    {
        if (items == null) { return; }
        // Copy quantities, never retain references to the client's mutable container/array.
        bank = count(items);
        remoteBank.clear();
        bankTime = time;
    }

    long getBankTime() { return bankTime; }

    boolean hasBank() { return bank != null; }

    /** Credit a confirmed remote transfer without claiming the whole bank was observed. */
    void creditBank(Map<Integer, Long> items)
    {
        items.forEach((id, quantity) ->
        {
            if (quantity > 0) { (bank == null ? remoteBank : bank).merge(canonicalize.applyAsInt(id), quantity, Long::sum); }
        });
    }

    Map<Integer, Long> remoteBankSnapshot() { return new LinkedHashMap<>(remoteBank); }

    void restoreRemoteBank(Map<Integer, Long> saved)
    {
        if (bank == null && saved != null) { remoteBank.putAll(saved); }
    }

    Wealth.Bank bankSnapshot()
    {
        return bank == null ? null : new Wealth.Bank(bankTime, bank);
    }

    void restoreBank(Wealth.Bank saved)
    {
        // An observation already received this session takes precedence over a disk load.
        if (bank == null && saved != null)
        {
            bank = new LinkedHashMap<>(saved.quantities);
            bankTime = saved.time;
        }
    }

    Map<Integer, long[]> combine(Item[] inventory, Item[] equipment)
    {
        // Called after logged-in updates settle. RuneLite can omit either empty carried
        // container until its first item arrives; neither is a prerequisite for tracking.
        Map<Integer, long[]> result = new LinkedHashMap<>();
        if (bank != null) { add(result, bank, 0); }
        else { add(result, remoteBank, 0); }
        add(result, count(inventory), 1);
        add(result, count(equipment), 2);
        return result;
    }

    private Map<Integer, Long> count(Item[] items)
    {
        Map<Integer, Long> result = new LinkedHashMap<>();
        if (items == null) { return result; }
        for (Item item : items)
        {
            if (item.getId() < 0 || item.getQuantity() <= 0 || isPlaceholder.test(item.getId())) { continue; }
            result.merge(canonicalize.applyAsInt(item.getId()), (long) item.getQuantity(), Long::sum);
        }
        return result;
    }

    private static void add(Map<Integer, long[]> result, Map<Integer, Long> counts, int location)
    {
        counts.forEach((id, quantity) -> result.computeIfAbsent(id, ignored -> new long[3])[location] = quantity);
    }
}
