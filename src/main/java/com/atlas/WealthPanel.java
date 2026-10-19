package com.atlas;

import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridLayout;
import java.awt.RenderingHints;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.text.NumberFormat;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTabbedPane;
import javax.swing.JTextField;
import javax.swing.JTable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.table.AbstractTableModel;
import javax.swing.table.DefaultTableCellRenderer;
import net.runelite.client.game.ItemManager;
import net.runelite.client.ui.PluginPanel;
import net.runelite.client.util.AsyncBufferedImage;

/** Swing only: never reads live game containers or performs disk/network IO. */
final class WealthPanel extends PluginPanel
{
    static final Color BACKGROUND = new Color(17, 19, 29);
    static final Color CARD = new Color(26, 29, 43);
    static final Color BLUE = new Color(64, 133, 255);
    static final Color MUTED = new Color(158, 170, 190);
    static final Color GREEN = new Color(68, 211, 148);
    static final Color RED = new Color(255, 111, 126);
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());
    private final ItemManager itemManager;
    private final Runnable takeSnapshot;
    private final JLabel amount = label("--", 29, BLUE);
    private final JLabel valueKind = label("Net Worth", 11, MUTED);
    private final JLabel breakdown = label("Open your bank to begin", 11, MUTED);
    private final JLabel bankSeen = label("", 11, MUTED);
    private final JLabel sessionChange = label("Value changed this session: --", 12, MUTED);
    private final JLabel status = label("Waiting for login and bank sync", 12, MUTED);
    private final JLabel syncNotice = label("History saved on this device. Not synced.", 11, MUTED);
    private final JButton syncHistory = button("Sync history now");
    private final JPanel topItems = new JPanel(new GridLayout(0, 1, 0, 6));
    private Wealth.Snapshot snapshot;
    private Wealth.State state;
    private boolean synced;
    private boolean bankObserved;
    private String statusMessage = "Waiting for login and bank data";
    private JDialog dialog;
    private Dashboard dashboard;

    WealthPanel(ItemManager itemManager, Runnable takeSnapshot)
    {
        this(itemManager, takeSnapshot, () -> {});
    }

    WealthPanel(ItemManager itemManager, Runnable takeSnapshot, Runnable syncNow)
    {
        super(false);
        this.itemManager = itemManager;
        this.takeSnapshot = takeSnapshot;
        setLayout(new BorderLayout());
        setBackground(BACKGROUND);
        setBorder(BorderFactory.createEmptyBorder(14, 10, 14, 10));
        JPanel content = vertical();
        content.add(label("ATLAS", 15, Color.WHITE));
        content.add(Box.createVerticalStrut(12));
        content.add(valueKind);
        content.add(amount);
        content.add(breakdown);
        content.add(Box.createVerticalStrut(8));
        content.add(bankSeen);
        content.add(Box.createVerticalStrut(12));
        content.add(sessionChange);
        content.add(Box.createVerticalStrut(10));
        status.setBorder(BorderFactory.createEmptyBorder(0, 0, 12, 0));
        content.add(status);
        JButton open = button("Open dashboard / Net Worth");
        open.addActionListener(e -> openDashboard());
        content.add(open);
        content.add(Box.createVerticalStrut(6));
        JButton sessions = button("Session History");
        sessions.addActionListener(e -> openDashboard(true));
        content.add(sessions);
        content.add(Box.createVerticalStrut(18));
        content.add(label("Highest Value", 11, MUTED));
        content.add(Box.createVerticalStrut(8));
        topItems.setOpaque(false);
        topItems.setAlignmentX(Component.LEFT_ALIGNMENT);
        content.add(topItems);
        content.add(Box.createVerticalStrut(20));
        content.add(label("Bank + inventory + equipment + cannon.", 11, MUTED));
        content.add(syncNotice);
        syncHistory.addActionListener(event -> syncNow.run());
        syncHistory.setVisible(false);
        content.add(syncHistory);
        add(content, BorderLayout.NORTH);
    }

    void updateSync(String message, boolean enabled)
    {
        syncNotice.setText("<html><body style='width:190px'>" + escape(message) + "</body></html>");
        syncHistory.setVisible(enabled);
        revalidate(); repaint();
    }

    void update(Wealth.Snapshot snapshot, Wealth.State state, boolean synced, boolean bankObserved, String message)
    {
        this.snapshot = snapshot;
        this.state = state;
        this.synced = synced;
        this.bankObserved = bankObserved;
        amount.setText(snapshot == null ? "--" : compact(snapshot.total()) + " GP");
        valueKind.setText(snapshot == null ? "Net Worth" : snapshot.bankTime == 0 ? "Known items only"
            : bankObserved ? "Net Worth" : "Estimated Net Worth");
        breakdown.setText(snapshot == null ? "Open your bank to begin" :
            "<html>• Bank " + (snapshot.bankTime == 0 ? "unknown" : compact(snapshot.bank()))
                + "<br>• Inventory " + compact(snapshot.inventory())
                + "<br>• Equipment " + compact(snapshot.equipment())
                + (snapshot.deployed() == 0 ? "" : "<br>• Deployed cannon " + compact(snapshot.deployed())) + "</html>");
        bankSeen.setText(snapshot == null || snapshot.bankTime == 0 ? "" :
            "<html>Bank last updated " + TIME.format(Instant.ofEpochMilli(snapshot.bankTime)) + "</html>");
        statusMessage = message.replace(
            "Saved bank contents + live inventory/equipment. Open your bank when convenient to refresh.", "").trim();
        status.setText("<html><body style='width:190px'>" + statusMessage + "</body></html>");
        status.setVisible(!statusMessage.isEmpty());
        Wealth.Session current = state == null ? null : state.activeSession;
        sessionChange.setText(current == null ? "Value changed this session: --" :
            "<html>Value changed this session<br><font color='" + (current.change < 0 ? "#ff6f7e" : "#44d394")
                + "'>" + signed(current.change) + " GP</font></html>");
        sessionChange.setToolTipText("Item gains/losses at fixed session prices; market changes excluded. Check containers to sync cached or inferred counts.");
        topItems.removeAll();
        if (snapshot != null)
        {
            snapshot.items.values().stream().sorted(Comparator.comparingLong(Wealth.Entry::value).reversed()).limit(6)
                .forEach(e -> topItems.add(label("<html>" + escape(e.name) + "<br><font color='#4085ff'>"
                    + compact(e.value()) + " GP</font></html>", 12, Color.WHITE)));
        }
        if (dashboard != null) { dashboard.refresh(); }
        revalidate();
        repaint();
    }

    private void openDashboard()
    {
        openDashboard(false);
    }

    private void openDashboard(boolean sessions)
    {
        if (dialog == null)
        {
            dialog = new JDialog(SwingUtilities.getWindowAncestor(this), "Atlas");
            dashboard = new Dashboard();
            dialog.setContentPane(dashboard);
            dialog.setMinimumSize(new Dimension(760, 580));
            dialog.setSize(980, 760);
            dialog.setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
            dialog.setDefaultCloseOperation(JDialog.HIDE_ON_CLOSE);
        }
        dashboard.refresh();
        if (sessions) { dashboard.tabs.setSelectedIndex(2); }
        dialog.setVisible(true);
        dialog.toFront();
    }

    void close()
    {
        if (dialog != null) { dialog.dispose(); dialog = null; dashboard = null; }
    }

    final class Dashboard extends JPanel
    {
        private final JLabel total = label("--", 32, BLUE);
        private final JLabel locations = label("", 13, MUTED);
        private final JLabel sync = label("", 12, MUTED);
        private final JButton record = button("Record snapshot");
        private final JTextField search = new JTextField();
        private final JComboBox<String> scope = new JComboBox<>(new String[]{"All", "Bank", "Inventory", "Equipment"});
        private final JComboBox<String> sort = new JComboBox<>(new String[]{"GE stack value", "GE unit price", "HA stack value", "Quantity", "Name A–Z"});
        private final JComboBox<String> filter = new JComboBox<>(new String[]{"All items", "Members", "Free to play", "Tradeable", "Untradeable", "No GE quote"});
        private boolean descending = true;
        private final JButton direction = button("↓");
        private final JPanel grid = new JPanel(new GridLayout(0, 6, 8, 8));
        private final JLabel matches = label("", 12, MUTED);
        private final JLabel pageLabel = label("", 12, MUTED);
        private final JButton previous = button("Previous");
        private final JButton next = button("Next");
        private int page;
        private final JComboBox<String> range = new JComboBox<>(new String[]{"24 hours", "7 days", "30 days", "1 year", "All history"});
        private final JComboBox<String> metric = new JComboBox<>(new String[]{"GE net worth", "HA value", "Unique items", "Market movement", "Item changes"});
        private final JLabel changes = label("", 15, Color.WHITE);
        private final JLabel coverage = label("", 12, MUTED);
        private final Graph graph = new Graph();
        private final JTabbedPane tabs = new JTabbedPane();
        private final SessionHistoryModel sessionModel = new SessionHistoryModel();

        Dashboard()
        {
            super(new BorderLayout(12, 12));
            setBackground(BACKGROUND);
            setBorder(BorderFactory.createEmptyBorder(20, 22, 18, 22));
            JPanel header = vertical();
            header.add(label("ATLAS", 23, Color.WHITE));
            header.add(total);
            header.add(locations);
            header.add(Box.createVerticalStrut(8));
            header.add(sync);
            add(header, BorderLayout.NORTH);

            tabs.addTab("Items", itemsTab());
            tabs.addTab("Net Worth", historyTab());
            tabs.addTab("Session History", sessionsTab());
            add(tabs, BorderLayout.CENTER);
            JPanel footer = new JPanel(new BorderLayout(12, 0));
            footer.setOpaque(false);
            footer.add(label("<html>GE guide estimates · Includes deployed cannon and tracked container contents<br>Other storage and GE offers are not included.</html>", 11, MUTED), BorderLayout.CENTER);
            record.addActionListener(e -> takeSnapshot.run());
            footer.add(record, BorderLayout.EAST);
            add(footer, BorderLayout.SOUTH);
        }

        private JPanel sessionsTab()
        {
            JPanel root = new JPanel(new BorderLayout(10, 12));
            root.setBackground(BACKGROUND);
            root.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
            root.add(label("<html>Market changes excluded · Item prices fixed within each session<br>Includes loot, gifts, trades and supplies used. Times are local.</html>", 12, MUTED), BorderLayout.NORTH);
            JTable table = new JTable(sessionModel)
            {
                @Override
                public String getToolTipText(MouseEvent event)
                {
                    int row = rowAtPoint(event.getPoint());
                    if (row < 0) { return null; }
                    Wealth.Session session = sessionModel.row(row);
                    return "<html>" + (session.interrupted ? "Recovered through the last saved update.<br>" : "")
                        + (session.estimated ? "Includes saved/unknown bank data; bank corrections are not counted as session gains.<br>" : "")
                        + "Session time runs from login to the last update/end.<br>"
                        + "Change/hour = value change divided by that exact elapsed time.<br>"
                        + "Market repricing excluded. This is an item-value change, not a trade-profit ledger.</html>";
                }
            };
            table.setRowHeight(32);
            table.setFillsViewportHeight(true);
            table.setBackground(CARD);
            table.setForeground(Color.WHITE);
            table.setGridColor(new Color(45, 50, 69));
            table.setDefaultRenderer(Object.class, new DefaultTableCellRenderer()
            {
                @Override
                public Component getTableCellRendererComponent(JTable target, Object value, boolean selected,
                                                                boolean focused, int row, int column)
                {
                    Component component = super.getTableCellRendererComponent(target, value, selected, focused, row, column);
                    int modelColumn = target.convertColumnIndexToModel(column);
                    component.setForeground(!selected && (modelColumn == 3 || modelColumn == 4)
                        ? sessionModel.row(row).change < 0 ? RED : GREEN : Color.WHITE);
                    return component;
                }
            });
            table.getColumnModel().getColumn(0).setPreferredWidth(185);
            table.getColumnModel().getColumn(1).setPreferredWidth(185);
            table.getColumnModel().getColumn(2).setPreferredWidth(110);
            table.getColumnModel().getColumn(3).setPreferredWidth(130);
            table.getColumnModel().getColumn(4).setPreferredWidth(120);
            table.getColumnModel().getColumn(5).setPreferredWidth(150);
            JScrollPane scroll = new JScrollPane(table);
            scroll.setColumnHeaderView(table.getTableHeader());
            scroll.getViewport().setBackground(CARD);
            root.add(scroll, BorderLayout.CENTER);
            root.add(label("<html>Newest first. Live = current login. Logging out saves the final result.<br>World hops stay in the same session. History starts after installing this feature.</html>", 11, MUTED), BorderLayout.SOUTH);
            return root;
        }

        private JPanel itemsTab()
        {
            JPanel root = new JPanel(new BorderLayout(8, 8));
            root.setBackground(BACKGROUND);
            root.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
            JPanel tools = vertical();
            search.setToolTipText("Search item names");
            search.putClientProperty("JTextField.placeholderText", "Search your items...");
            search.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));
            tools.add(search);
            JPanel choices = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 8));
            choices.setOpaque(false);
            choices.add(scope); choices.add(sort); choices.add(direction); choices.add(filter);
            tools.add(choices);
            tools.add(matches);
            root.add(tools, BorderLayout.NORTH);
            grid.setBackground(BACKGROUND);
            JPanel gridContainer = new JPanel(new BorderLayout());
            gridContainer.setBackground(BACKGROUND);
            gridContainer.add(grid, BorderLayout.NORTH);
            JScrollPane scroll = new JScrollPane(gridContainer);
            scroll.setBorder(BorderFactory.createEmptyBorder());
            scroll.getVerticalScrollBar().setUnitIncrement(25);
            root.add(scroll, BorderLayout.CENTER);
            JPanel paging = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
            paging.setOpaque(false);
            paging.add(previous); paging.add(pageLabel); paging.add(next);
            root.add(paging, BorderLayout.SOUTH);
            search.getDocument().addDocumentListener(new DocumentListener()
            {
                public void insertUpdate(DocumentEvent e) { changed(); }
                public void removeUpdate(DocumentEvent e) { changed(); }
                public void changedUpdate(DocumentEvent e) { changed(); }
                private void changed() { page = 0; refreshItems(); }
            });
            scope.addActionListener(e -> { page = 0; refreshItems(); });
            sort.addActionListener(e -> { page = 0; refreshItems(); });
            filter.addActionListener(e -> { page = 0; refreshItems(); });
            direction.addActionListener(e -> { descending = !descending; direction.setText(descending ? "↓" : "↑"); refreshItems(); });
            previous.addActionListener(e -> { --page; refreshItems(); });
            next.addActionListener(e -> { ++page; refreshItems(); });
            return root;
        }

        private JPanel historyTab()
        {
            JPanel root = new JPanel(new BorderLayout(10, 16));
            root.setBackground(BACKGROUND);
            root.setBorder(BorderFactory.createEmptyBorder(12, 0, 0, 0));
            JPanel tools = vertical();
            JPanel selectors = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
            selectors.setOpaque(false);
            selectors.add(range); selectors.add(metric);
            tools.add(selectors);
            tools.add(Box.createVerticalStrut(20));
            tools.add(changes);
            tools.add(Box.createVerticalStrut(10));
            tools.add(coverage);
            root.add(tools, BorderLayout.NORTH);
            root.add(graph, BorderLayout.CENTER);
            root.add(label("<html>Blue = net worth · Green = market movement · Amber = item changes<br>Item changes include loot, trades, spending and supplies used; this is not a profit ledger.<br>Points every 5 minutes while playing, on logout, or when you record a snapshot.<br>Totals may use saved bank contents until your next visit.<br>Prices are GE guide estimates; missing quotes may retain a previous quote.</html>", 11, MUTED), BorderLayout.SOUTH);
            range.setSelectedIndex(2);
            range.addActionListener(e -> refreshHistory());
            metric.addActionListener(e -> refreshHistory());
            return root;
        }

        void refresh()
        {
            total.setText(snapshot == null ? "--" : compact(snapshot.total()) + " GP"
                + (snapshot.bankTime == 0 ? " · Known items only" : bankObserved ? "" : " · Estimated net worth"));
            locations.setText(snapshot == null ? "Open your bank to start tracking." :
                "Bank " + (snapshot.bankTime == 0 ? "unknown" : compact(snapshot.bank())) + "   +   Inventory " + compact(snapshot.inventory())
                    + "   +   Equipment " + compact(snapshot.equipment())
                    + (snapshot.deployed() == 0 ? "" : "   +   Cannon " + compact(snapshot.deployed()))
                    + "     |     " + snapshot.items.size() + " unique items");
            sync.setText(snapshot == null ? "<html>" + statusMessage + "</html>" : "<html>"
                + (snapshot.bankTime == 0 ? "Bank not recorded yet" : (bankObserved ? "Bank observed this session" : "Saved bank contents")
                + " · Bank last seen " + TIME.format(Instant.ofEpochMilli(snapshot.bankTime)))
                + (statusMessage.isEmpty() ? "" : "<br>" + statusMessage)
                + (snapshot.unpriced() > 0 ? "<br>" + snapshot.unpriced() + " items have no GE quote" : "") + "</html>");
            record.setEnabled(synced);
            refreshItems();
            refreshHistory();
            sessionModel.setState(state);
            revalidate();
            repaint();
        }

        private long quantity(Wealth.Entry entry)
        {
            switch (scope.getSelectedIndex())
            {
                case 1: return entry.bank;
                case 2: return entry.inventory;
                case 3: return entry.equipment;
                default: return entry.quantity();
            }
        }

        private boolean matchesFilter(Wealth.Entry e)
        {
            switch (filter.getSelectedIndex())
            {
                case 1: return e.members;
                case 2: return !e.members;
                case 3: return e.tradeable;
                case 4: return !e.tradeable;
                case 5: return e.unpriced;
                default: return true;
            }
        }

        private void refreshItems()
        {
            String query = search.getText().trim().toLowerCase(Locale.ROOT);
            List<Wealth.Entry> entries = snapshot == null ? new ArrayList<>() : snapshot.items.values().stream()
                .filter(e -> quantity(e) > 0 && e.name.toLowerCase(Locale.ROOT).contains(query) && matchesFilter(e))
                .collect(Collectors.toList());
            Comparator<Wealth.Entry> comparator;
            switch (sort.getSelectedIndex())
            {
                case 1: comparator = Comparator.comparingLong(e -> e.price); break;
                case 2: comparator = Comparator.comparingLong(e -> quantity(e) * e.alch); break;
                case 3: comparator = Comparator.comparingLong(this::quantity); break;
                case 4: comparator = Comparator.comparing(e -> e.name.toLowerCase(Locale.ROOT)); break;
                default: comparator = Comparator.comparingLong(e -> quantity(e) * e.price);
            }
            if ((sort.getSelectedIndex() != 4 && descending) || (sort.getSelectedIndex() == 4 && !descending))
            { comparator = comparator.reversed(); }
            entries.sort(comparator.thenComparingInt(e -> e.id));
            int pages = Math.max(1, (entries.size() + 59) / 60);
            page = Math.max(0, Math.min(page, pages - 1));
            grid.removeAll();
            for (int i = page * 60; i < Math.min(entries.size(), (page + 1) * 60); ++i)
            { grid.add(itemCard(entries.get(i))); }
            long visibleTotal = entries.stream().mapToLong(e -> quantity(e) * e.price).sum();
            matches.setText(entries.size() + " matching items · " + compact(visibleTotal) + " GP in selected location");
            previous.setEnabled(page > 0); next.setEnabled(page + 1 < pages);
            pageLabel.setText("Page " + (page + 1) + " of " + pages);
            grid.revalidate(); grid.repaint();
        }

        private JPanel itemCard(Wealth.Entry entry)
        {
            JPanel card = new JPanel(new BorderLayout(0, 3));
            card.setBackground(CARD);
            card.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(new Color(40, 44, 63)),
                BorderFactory.createEmptyBorder(8, 5, 8, 5)));
            card.setPreferredSize(new Dimension(132, 112));
            String tooltip = "<html>" + escape(entry.name) + "<br>Quantity: " + exact(quantity(entry))
                + "<br>GE each: " + exact(entry.price) + " GP<br>GE stack: " + exact(quantity(entry) * entry.price)
                + " GP<br>HA stack: " + exact(quantity(entry) * entry.alch) + " GP<br>Bank: " + exact(entry.bank)
                + " · Inventory: " + exact(entry.inventory) + " · Equipment: " + exact(entry.equipment)
                + (entry.deployed == 0 ? "" : " · Deployed cannon: " + exact(entry.deployed)) + "</html>";
            JLabel quantity = label(exact(quantity(entry)), 11, new Color(255, 211, 94));
            JLabel image = new JLabel("", SwingConstants.CENTER);
            image.setPreferredSize(new Dimension(38, 34));
            if (itemManager != null)
            {
                AsyncBufferedImage sprite = itemManager.getImage(entry.id);
                if (sprite != null) { sprite.addTo(image); }
            }
            JPanel top = new JPanel(new BorderLayout()); top.setOpaque(false);
            top.add(quantity, BorderLayout.NORTH); top.add(image, BorderLayout.CENTER);
            card.add(top, BorderLayout.CENTER);
            JPanel bottom = new JPanel(new GridLayout(2, 1)); bottom.setOpaque(false);
            JLabel name = label(entry.name, 11, Color.WHITE); name.setHorizontalAlignment(SwingConstants.CENTER);
            JLabel value = label(entry.unpriced ? "No GE quote" : compact(quantity(entry) * entry.price) + " GP", 12, BLUE);
            value.setHorizontalAlignment(SwingConstants.CENTER);
            bottom.add(name); bottom.add(value);
            card.add(bottom, BorderLayout.SOUTH);
            card.setToolTipText(tooltip);
            image.setToolTipText(tooltip); name.setToolTipText(tooltip); value.setToolTipText(tooltip);
            return card;
        }

        private void refreshHistory()
        {
            long[] periods = {86_400_000L, 7 * 86_400_000L, 30 * 86_400_000L, 365 * 86_400_000L, Long.MAX_VALUE};
            long cutoff = periods[range.getSelectedIndex()] == Long.MAX_VALUE ? 0 : System.currentTimeMillis() - periods[range.getSelectedIndex()];
            List<Wealth.Point> points = state == null ? Collections.emptyList() : state.history.stream()
                .filter(p -> p.time >= cutoff).collect(Collectors.toList());
            if (points.isEmpty())
            {
                changes.setText("No snapshots in this period yet.");
                coverage.setText(snapshot != null && snapshot.bankTime > 0 ? "Use Record snapshot to add a point."
                    : "Carried values update now. Open your bank once to start net-worth history.");
            }
            else
            {
                Wealth.Point first = points.get(0), last = points.get(points.size() - 1);
                boolean attributed = points.stream().skip(1).allMatch(p -> p.attributed);
                changes.setText("<html>Net change " + signed(last.total - first.total) + " GP"
                    + (attributed ? "<br><font color='#44d394'>Market " + signed(last.market - first.market) + " GP</font>"
                        + " &nbsp; · &nbsp; <font color='#ffd35e'>Item changes " + signed(last.items - first.items) + " GP</font>"
                        : "<br>Market/item split unavailable across an unsynced device gap.") + "</html>");
                coverage.setText(points.size() == 1 ? "First snapshot saved. More points will appear as you play." :
                    TIME.format(Instant.ofEpochMilli(first.time)) + " – " + TIME.format(Instant.ofEpochMilli(last.time))
                        + " · " + points.size() + " recorded points (local time)");
            }
            graph.setData(points, metric.getSelectedIndex());
        }
    }

    static final class SessionHistoryModel extends AbstractTableModel
    {
        private final List<Wealth.Session> rows = new ArrayList<>();
        private final List<Boolean> live = new ArrayList<>();
        private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, yyyy HH:mm").withZone(ZoneId.systemDefault());

        void setState(Wealth.State state)
        {
            rows.clear(); live.clear();
            if (state != null)
            {
                if (state.activeSession != null) { rows.add(state.activeSession); live.add(true); }
                for (int i = state.sessions.size() - 1; i >= 0; --i) { rows.add(state.sessions.get(i)); live.add(false); }
            }
            fireTableDataChanged();
        }

        Wealth.Session row(int row) { return rows.get(row); }
        public int getRowCount() { return rows.size(); }
        public int getColumnCount() { return 6; }
        public String getColumnName(int column)
        { return new String[]{"Session start", "Last update / end", "Session time", "Value change", "Change/hour", "Status"}[column]; }
        public Object getValueAt(int row, int column)
        {
            Wealth.Session session = rows.get(row);
            switch (column)
            {
                case 0: return DATE.format(Instant.ofEpochMilli(session.start));
                case 1: return DATE.format(Instant.ofEpochMilli(session.end));
                case 2: return duration(session.elapsedMillis());
                case 3: return signed(session.change) + " GP";
                case 4: return session.elapsedMillis() == 0 ? "--" : signed(session.changePerHour()) + " GP/h";
                default: return (live.get(row) ? "Live" : session.interrupted ? "Interrupted" : "Saved")
                    + (session.estimated ? " · Estimate" : "");
            }
        }

        private static String duration(long millis)
        {
            long seconds = millis / 1000;
            if (seconds < 60) { return seconds + " sec"; }
            long minutes = seconds / 60;
            if (minutes < 60) { return minutes + " min"; }
            return minutes / 60 + "h " + minutes % 60 + "m";
        }
    }

    static final class Graph extends JPanel
    {
        private List<Wealth.Point> points = Collections.emptyList();
        private int metric;
        private long origin;
        Graph()
        {
            setBackground(CARD);
            setPreferredSize(new Dimension(600, 300));
            setToolTipText("");
        }

        void setData(List<Wealth.Point> points, int metric)
        {
            this.points = points;
            this.metric = metric;
            origin = points.isEmpty() ? 0 : metric == 3 ? points.get(0).market : metric == 4 ? points.get(0).items : 0;
            repaint();
        }

        private long value(Wealth.Point p)
        {
            switch (metric)
            {
                case 1: return p.alch;
                case 2: return p.unique;
                case 3: return p.market - origin;
                case 4: return p.items - origin;
                default: return p.total;
            }
        }

        @Override
        protected void paintComponent(Graphics graphics)
        {
            super.paintComponent(graphics);
            Graphics2D g = (Graphics2D) graphics.create();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            int left = 70, right = getWidth() - 24, top = 28, bottom = getHeight() - 38;
            if (metric >= 3 && points.stream().skip(1).anyMatch(p -> !p.attributed))
            {
                g.setColor(MUTED);
                g.drawString("Market/item split unavailable across an unsynced device gap.", left, Math.max(50, getHeight() / 2));
                g.dispose(); return;
            }
            if (points.isEmpty())
            {
                g.setColor(MUTED); g.drawString("Your history starts with the first bank sync.", left, Math.max(50, getHeight() / 2));
                g.dispose(); return;
            }
            long min = points.stream().mapToLong(this::value).min().orElse(0);
            long max = points.stream().mapToLong(this::value).max().orElse(0);
            double padding = Math.max(1, (max - (double) min) * .15);
            double low = min - padding, high = max + padding;
            if (metric < 3) { low = Math.max(0, low); }
            if (high <= low) { high = low + 1; }
            for (int line = 0; line <= 4; ++line)
            {
                int y = top + (bottom - top) * line / 4;
                g.setColor(new Color(45, 50, 69)); g.drawLine(left, y, right, y);
                g.setColor(MUTED); g.drawString(compact((long) (high - (high - low) * line / 4)), 8, y + 4);
            }
            long firstTime = points.get(0).time, lastTime = points.get(points.size() - 1).time;
            Path2D path = new Path2D.Double();
            List<int[]> coordinates = new ArrayList<>();
            for (Wealth.Point point : points)
            {
                int x = firstTime == lastTime ? (left + right) / 2 : left + (int) ((right - left) * (point.time - (double) firstTime) / (lastTime - (double) firstTime));
                int y = bottom - (int) ((bottom - top) * (value(point) - low) / (high - low));
                if (coordinates.isEmpty()) { path.moveTo(x, y); } else { path.lineTo(x, y); }
                coordinates.add(new int[]{x, y});
            }
            Color color = metric == 3 ? GREEN : metric == 4 ? new Color(255, 211, 94) : BLUE;
            if (coordinates.size() > 1)
            {
                Path2D area = (Path2D) path.clone();
                area.lineTo(coordinates.get(coordinates.size() - 1)[0], bottom); area.lineTo(coordinates.get(0)[0], bottom); area.closePath();
                g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), 32)); g.fill(area);
            }
            g.setStroke(new BasicStroke(2)); g.setColor(color); g.draw(path);
            if (coordinates.size() <= 100)
            { for (int[] p : coordinates) { g.fillOval(p[0] - 3, p[1] - 3, 6, 6); } }
            g.setColor(MUTED);
            String start = TIME.format(Instant.ofEpochMilli(firstTime)), end = TIME.format(Instant.ofEpochMilli(lastTime));
            g.drawString(start, left, bottom + 24);
            g.drawString(end, right - g.getFontMetrics().stringWidth(end), bottom + 24);
            g.dispose();
        }

        @Override
        public String getToolTipText(MouseEvent event)
        {
            if (points.isEmpty()) { return null; }
            if (metric >= 3 && points.stream().skip(1).anyMatch(p -> !p.attributed))
            { return "Net worth remains available; this gap cannot be split accurately into market and item changes."; }
            long first = points.get(0).time, last = points.get(points.size() - 1).time;
            double fraction = Math.max(0, Math.min(1, (event.getX() - 70.0) / Math.max(1, getWidth() - 94.0)));
            double time = first + fraction * (last - (double) first);
            Wealth.Point nearest = points.stream().min(Comparator.comparingDouble(p -> Math.abs(p.time - time))).get();
            return TIME.format(Instant.ofEpochMilli(nearest.time)) + " · " + exact(value(nearest)) + (metric == 2 ? " items" : " GP");
        }
    }

    private static JPanel vertical()
    {
        JPanel panel = new JPanel(); panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS)); panel.setOpaque(false); return panel;
    }

    private static JLabel label(String text, int size, Color color)
    {
        JLabel label = new JLabel(text)
        {
            @Override
            public Dimension getMaximumSize()
            {
                return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
            }
        };
        label.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, size)); label.setForeground(color);
        label.setAlignmentX(Component.LEFT_ALIGNMENT); return label;
    }

    private static JButton button(String text)
    {
        JButton button = new JButton(text); button.setAlignmentX(Component.LEFT_ALIGNMENT); return button;
    }

    static String compact(long value)
    {
        double abs = Math.abs((double) value);
        if (abs >= 1_000_000_000) { return String.format(Locale.ROOT, "%.2fB", value / 1_000_000_000.0); }
        if (abs >= 1_000_000) { return String.format(Locale.ROOT, "%.2fM", value / 1_000_000.0); }
        if (abs >= 1000) { return String.format(Locale.ROOT, "%.1fK", value / 1000.0); }
        return Long.toString(value);
    }

    private static String exact(long value) { return NumberFormat.getIntegerInstance(Locale.US).format(value); }
    static String signed(long value) { return (value > 0 ? "+" : "") + compact(value); }
    private static String escape(String text) { return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;"); }
}
