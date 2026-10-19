package com.atlas;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Immutable values shared between the client, Swing and storage threads. */
final class Wealth
{
    static final class Session
    {
        final long start, end, change;
        final boolean estimated, interrupted;

        Session(long start, long end, long change, boolean estimated, boolean interrupted)
        {
            this.start = start;
            this.end = end;
            this.change = change;
            this.estimated = estimated;
            this.interrupted = interrupted;
        }

        long elapsedMillis() { return Math.max(0, end - start); }

        long changePerHour()
        {
            long elapsed = elapsedMillis();
            return elapsed == 0 ? 0 : Math.round(change * (3_600_000.0 / elapsed));
        }
    }

    static final class Bank
    {
        final long time;
        final Map<Integer, Long> quantities;

        Bank(long time, Map<Integer, Long> quantities)
        {
            this.time = time;
            this.quantities = Collections.unmodifiableMap(new LinkedHashMap<>(quantities));
        }

        static Bank from(Snapshot snapshot)
        {
            if (snapshot == null || snapshot.bankTime == 0) { return null; }
            Map<Integer, Long> quantities = new LinkedHashMap<>();
            snapshot.items.forEach((id, entry) ->
            {
                long loose = entry.bank - entry.bankPouch - entry.bankSack;
                if (loose > 0) { quantities.put(id, loose); }
            });
            return new Bank(snapshot.bankTime, quantities);
        }
    }

    static final class Entry
    {
        final int id;
        final String name;
        // bankSack is the persisted legacy name for ALL non-rune-pouch stored contents in the bank.
        final long bank, inventory, equipment, price, alch, bankPouch, bankSack, deployed;
        final boolean members, tradeable, unpriced;

        Entry(int id, String name, long bank, long inventory, long equipment,
              long price, long alch, boolean members, boolean tradeable, boolean unpriced)
        {
            this(id, name, bank, inventory, equipment, price, alch, members, tradeable, unpriced, 0);
        }

        Entry(int id, String name, long bank, long inventory, long equipment,
              long price, long alch, boolean members, boolean tradeable, boolean unpriced, long bankPouch)
        {
            this(id, name, bank, inventory, equipment, price, alch, members, tradeable, unpriced, bankPouch, 0);
        }

        Entry(int id, String name, long bank, long inventory, long equipment,
              long price, long alch, boolean members, boolean tradeable, boolean unpriced, long bankPouch, long bankSack)
        {
            this(id, name, bank, inventory, equipment, price, alch, members, tradeable, unpriced, bankPouch, bankSack, 0);
        }

        Entry(int id, String name, long bank, long inventory, long equipment,
              long price, long alch, boolean members, boolean tradeable, boolean unpriced,
              long bankPouch, long bankSack, long deployed)
        {
            this.id = id;
            this.name = name;
            this.bank = bank;
            this.bankPouch = bankPouch;
            this.bankSack = bankSack;
            this.deployed = deployed;
            this.inventory = inventory;
            this.equipment = equipment;
            this.price = price;
            this.alch = alch;
            this.members = members;
            this.tradeable = tradeable;
            this.unpriced = unpriced;
        }

        long quantity() { return bank + inventory + equipment + deployed; }
        long value() { return quantity() * price; }
        long haValue() { return quantity() * alch; }
    }

    static final class Snapshot
    {
        final long time, bankTime;
        final Map<Integer, Entry> items;

        Snapshot(long time, long bankTime, Map<Integer, Entry> items)
        {
            this.time = time;
            this.bankTime = bankTime;
            this.items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        }

        long bank() { return items.values().stream().mapToLong(e -> e.bank * e.price).sum(); }
        long inventory() { return items.values().stream().mapToLong(e -> e.inventory * e.price).sum(); }
        long equipment() { return items.values().stream().mapToLong(e -> e.equipment * e.price).sum(); }
        long deployed() { return items.values().stream().mapToLong(e -> e.deployed * e.price).sum(); }
        long total() { return bank() + inventory() + equipment() + deployed(); }
        long alch() { return items.values().stream().mapToLong(Entry::haValue).sum(); }
        long unpriced() { return items.values().stream().filter(e -> e.unpriced).count(); }
    }

    static final class Point
    {
        final long time, total, alch, unique, market, items;
        final boolean attributed;

        Point(long time, long total, long alch, long unique, long market, long items)
        {
            this(time, total, alch, unique, market, items, true);
        }

        Point(long time, long total, long alch, long unique, long market, long items, boolean attributed)
        {
            this.time = time;
            this.total = total;
            this.alch = alch;
            this.unique = unique;
            this.market = market;
            this.items = items;
            this.attributed = attributed;
        }
    }

    /** Revalue the PREVIOUS holdings at today's prices, then attribute the remainder to item changes. */
    static Point point(Snapshot previous, Snapshot next, Point last, Map<Integer, Long> quotes)
    {
        long market = 0;
        if (previous != null)
        {
            for (Entry old : previous.items.values())
            {
                long price = quotes.getOrDefault(old.id, old.price);
                market += old.quantity() * (price - old.price);
            }
        }
        long change = previous == null ? 0 : next.total() - previous.total();
        return new Point(next.time, next.total(), next.alch(), next.items.size(),
            (last == null ? 0 : last.market) + market,
            (last == null ? 0 : last.items) + change - market);
    }

    static final class State
    {
        final String profile;
        final Snapshot latest;
        final List<Point> history;
        final long revision;
        final Bank bank;
        final List<Session> sessions;
        final Session activeSession;
        final Map<Integer, Long> herbSack;
        final Map<String, Map<Integer, Long>> extraContainers;

        State(String profile, Snapshot latest, List<Point> history, long revision)
        {
            this(profile, latest, history, revision, Bank.from(latest));
        }

        State(String profile, Snapshot latest, List<Point> history, long revision, Bank bank)
        {
            this(profile, latest, history, revision, bank, Collections.emptyList(), null);
        }

        State(String profile, Snapshot latest, List<Point> history, long revision, Bank bank,
              List<Session> sessions, Session activeSession)
        {
            this(profile, latest, history, revision, bank, sessions, activeSession, null);
        }

        State(String profile, Snapshot latest, List<Point> history, long revision, Bank bank,
              List<Session> sessions, Session activeSession, Map<Integer, Long> herbSack)
        {
            this(profile, latest, history, revision, bank, sessions, activeSession, herbSack, Collections.emptyMap());
        }

        State(String profile, Snapshot latest, List<Point> history, long revision, Bank bank,
              List<Session> sessions, Session activeSession, Map<Integer, Long> herbSack,
              Map<String, Map<Integer, Long>> extraContainers)
        {
            this.profile = profile;
            this.latest = latest;
            this.history = Collections.unmodifiableList(new ArrayList<>(history));
            this.revision = revision;
            this.bank = bank;
            this.sessions = Collections.unmodifiableList(new ArrayList<>(sessions));
            this.activeSession = activeSession;
            this.herbSack = herbSack == null ? null : Collections.unmodifiableMap(new LinkedHashMap<>(herbSack));
            Map<String, Map<Integer, Long>> copy = new LinkedHashMap<>();
            extraContainers.forEach((key, items) -> copy.put(key, Collections.unmodifiableMap(new LinkedHashMap<>(items))));
            this.extraContainers = Collections.unmodifiableMap(copy);
        }

        static State empty(String profile)
        {
            return new State(profile, null, Collections.emptyList(), 0);
        }

        State append(Snapshot snapshot, Map<Integer, Long> quotes)
        {
            if (snapshot.bankTime == 0) { throw new IllegalArgumentException("Net-worth history requires a known bank"); }
            List<Point> points = new ArrayList<>(history);
            points.add(point(latest, snapshot, points.isEmpty() ? null : points.get(points.size() - 1), quotes));
            Bank observed = Bank.from(snapshot);
            Bank newest = bank != null && (observed == null || bank.time > observed.time) ? bank : observed;
            return new State(profile, snapshot, points, revision + 1, newest, sessions, activeSession, herbSack, extraContainers);
        }

        State withBank(Bank bank)
        {
            return new State(profile, latest, history, revision + 1, bank, sessions, activeSession, herbSack, extraContainers);
        }

        State withActiveSession(Session session)
        {
            return new State(profile, latest, history, revision + 1, bank, sessions, session, herbSack, extraContainers);
        }

        State withHerbSack(Map<Integer, Long> contents)
        {
            return new State(profile, latest, history, revision + 1, bank, sessions, activeSession, contents, extraContainers);
        }

        State withExtraContainers(Map<String, Map<Integer, Long>> contents)
        {
            return new State(profile, latest, history, revision + 1, bank, sessions, activeSession, herbSack, contents);
        }

        State closeSession(boolean interrupted)
        {
            if (activeSession == null) { return this; }
            List<Session> completed = new ArrayList<>(sessions);
            completed.add(new Session(activeSession.start, activeSession.end, activeSession.change,
                activeSession.estimated, interrupted));
            return new State(profile, latest, history, revision + 1, bank, completed, null, herbSack, extraContainers);
        }
    }
}
