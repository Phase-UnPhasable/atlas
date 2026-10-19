package com.atlas;

import com.google.gson.Gson;
import com.google.inject.Provides;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.ArrayList;
import java.util.List;
import java.util.HashSet;
import java.util.Set;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import javax.inject.Inject;
import javax.swing.SwingUtilities;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.ChatMessageType;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.GameState;
import net.runelite.api.ItemComposition;
import net.runelite.api.Item;
import net.runelite.api.MenuAction;
import net.runelite.api.coords.WorldPoint;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.MenuOptionClicked;
import net.runelite.api.events.ItemDespawned;
import net.runelite.api.events.ItemQuantityChanged;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.api.widgets.WidgetUtil;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.account.AccountSession;
import net.runelite.client.account.SessionManager;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ClientShutdown;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.ui.overlay.OverlayManager;
import net.runelite.client.util.Filepath;
import net.runelite.client.util.Text;

@Slf4j
@PluginDescriptor(name = "Atlas", internalName = "atlas",
    description = "Track bank value, net worth and session gains/losses with optional cross-device history sync",
    tags = {"bank", "wealth", "net worth", "profit", "loss", "session", "history", "equipment"})
public class AtlasPlugin extends Plugin
{
    private static final long SAMPLE_INTERVAL = 5 * 60_000L;
    private static final java.util.regex.Pattern MINED_GEM = java.util.regex.Pattern.compile(
        "You just (?:found|mined) (?:a|an) (?:piece of )?([A-Za-z ]+)!", java.util.regex.Pattern.CASE_INSENSITIVE);
    @Inject private Client client;
    @Inject private ClientThread clientThread;
    @Inject private ItemManager itemManager;
    @Inject private ConfigManager configManager;
    @Inject private AtlasConfig config;
    @Inject private SessionManager sessionManager;
    @Inject private ClientToolbar toolbar;
    @Inject private ScheduledExecutorService executor;
    @Inject private Gson gson;
    @Inject private OverlayManager overlayManager;

    private volatile boolean active;
    private volatile long generation;
    private volatile long lifetime;
    private WealthPanel panel;
    private NavigationButton navigation;
    private InventorySessionOverlay inventorySessionOverlay;
    private WealthStore store;
    private CompletableFuture<Void> ioTail = CompletableFuture.completedFuture(null);
    private Filepath directory;
    // All fields below are only accessed on RuneLite's client thread.
    private String profile;
    private Wealth.State saved;
    private Wealth.Snapshot live;
    private SessionTracker session;
    private long lastSessionSave;
    private String syncDeviceId;
    private boolean syncBusy;
    private boolean syncFlushRequested;
    private long lastSyncAttempt;
    private String syncMessage = "History saved on this device. Not synced.";
    private Map<Integer, Long> quotes = new HashMap<>();
    private Map<Integer, Long> pouchContents = new HashMap<>();
    private final HerbSackTracker herbSack = new HerbSackTracker();
    private final CannonTracker cannon = new CannonTracker();
    private final SoulBearerTracker soulBearer = new SoulBearerTracker();
    private long soulBearerSpent;
    private final Map<Integer, Long> soulBearerTransfers = new HashMap<>();
    private boolean sackPresent;
    private final List<BagTracker> bags = new ArrayList<>();
    private final Set<BagTracker.Kind> presentBags = new HashSet<>();
    private int pickupId = -1, pickupTick;
    private WorldPoint pickupTile;
    private final ContainerTransfer bulkDeposit = new ContainerTransfer();
    private final Set<BagTracker.Kind> bulkEligibleBags = new HashSet<>();
    private boolean bulkHerbs;
    private Map<Integer, Long> bulkBeforeStored = new HashMap<>();
    private final HoldingsTracker containers = new HoldingsTracker(
        id -> SoulBearerTracker.canonicalHead(itemManager.canonicalize(id)),
        id -> itemManager.getItemComposition(id).getPlaceholderTemplateId() != -1);
    private boolean loading, storageFailed, bankSynced, dirty;
    private long bankTime, lastCapture;
    private int ticks, settleUntil;
    private int seedViewSince = -1;
    private int lastCannonStage = -1;
    private boolean lastCannonDecayed;

    @Provides
    AtlasConfig provideConfig(ConfigManager manager) { return manager.getConfig(AtlasConfig.class); }

    @Override
    protected void startUp()
    {
        active = true;
        long version = ++lifetime;
        store = new WealthStore(gson);
        inventorySessionOverlay = new InventorySessionOverlay(client);
        overlayManager.add(inventorySessionOverlay);
        bags.clear();
        for (BagTracker.Kind kind : BagTracker.Kind.values())
        {
            bags.add(new BagTracker(kind, this::resolveItem, id ->
            {
                String name = itemManager.getItemComposition(id).getName().toLowerCase(java.util.Locale.ROOT);
                return name.contains("seed") || name.contains("spore") || name.equals("frags");
            }));
        }
        SwingUtilities.invokeLater(() ->
        {
            if (!active || lifetime != version) { return; }
            panel = new WealthPanel(itemManager, () -> clientThread.invoke(this::manualSnapshot),
                () -> clientThread.invoke(this::syncHistoryNow));
            navigation = NavigationButton.builder().tooltip("Atlas")
                .icon(icon()).priority(6).panel(panel).build();
            toolbar.addNavigation(navigation);
        });
        clientThread.invoke(() -> { if (active) { resetSession(); } });
    }

    @Override
    protected void shutDown()
    {
        active = false;
        ++lifetime;
        ++generation;
        if (inventorySessionOverlay != null)
        {
            overlayManager.remove(inventorySessionOverlay);
            inventorySessionOverlay = null;
        }
        // Saves use the shared executor and immutable state; no blocking shutdown or lost final task.
        clientThread.invoke(() ->
        {
            finishSession();
            recordLive();
            resetSession();
        });
        SwingUtilities.invokeLater(() ->
        {
            if (navigation != null) { toolbar.removeNavigation(navigation); navigation = null; }
            if (panel != null) { panel.close(); panel = null; }
        });
    }

    @Subscribe
    public void onGameStateChanged(GameStateChanged event)
    {
        if (event.getGameState() == GameState.LOGIN_SCREEN)
        {
            finishSession();
            recordLive();
            resetSession();
            publish("Log in to resume live inventory and equipment tracking.");
        }
        else if (event.getGameState() == GameState.HOPPING)
        {
            herbSack.cancelTransfer();
            bags.forEach(BagTracker::cancelTransfer);
            pickupTile = null;
            bulkDeposit.cancel();
            soulBearer.cancel();
            // Hopping worlds preserves the login session and its fixed prices.
            recordLive();
            bankSynced = false;
            dirty = true;
            settleUntil = ticks + 2;
        }
    }

    @Subscribe
    public void onClientShutdown(ClientShutdown event)
    {
        CompletableFuture<Void> flushed = new CompletableFuture<>();
        event.waitFor(flushed);
        clientThread.invoke(() ->
        {
            try
            {
                finishSession();
                recordLive();
                ioTail.whenComplete((ignored, error) ->
                {
                    if (error == null) { flushed.complete(null); }
                    else { flushed.completeExceptionally(error); }
                });
            }
            catch (RuntimeException ex) { flushed.completeExceptionally(ex); }
        });
    }

    private void resetSession()
    {
        ++generation;
        profile = null;
        saved = null;
        live = null;
        session = null;
        syncBusy = false; syncFlushRequested = false; lastSyncAttempt = 0; syncDeviceId = null;
        if (inventorySessionOverlay != null) { inventorySessionOverlay.update(null); }
        lastSessionSave = 0;
        quotes = new HashMap<>();
        pouchContents = new HashMap<>();
        herbSack.reset();
        cannon.reset(); lastCannonStage = -1; lastCannonDecayed = false;
        soulBearer.cancel(); soulBearerSpent = 0; soulBearerTransfers.clear();
        sackPresent = false;
        bags.forEach(BagTracker::reset);
        presentBags.clear();
        pickupTile = null;
        bulkDeposit.cancel(); bulkEligibleBags.clear(); bulkHerbs = false;
        bulkBeforeStored.clear();
        containers.reset();
        bankSynced = false;
        loading = false;
        storageFailed = false;
        dirty = true;
        bankTime = 0;
        lastCapture = 0;
        seedViewSince = -1;
        settleUntil = ticks + 2;
    }

    @Subscribe
    public void onChatMessage(ChatMessage event)
    {
        if (!active || loading || saved == null || storageFailed
            || (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)) { return; }
        String message = Text.removeTags(event.getMessage()).trim();
        if (herbSack.chat(message))
        {
            dirty = true;
            checkpointHerbSack();
        }
        for (BagTracker bag : bags)
        {
            if (bag.chat(message, ticks)) { dirty = true; checkpointExtraContainers(); }
        }
        java.util.regex.Matcher mined = MINED_GEM.matcher(message);
        if (mined.matches()) { queueGem(BagTracker.gemId(mined.group(1)), 1); }
    }

    @Subscribe
    public void onMenuOptionClicked(MenuOptionClicked event)
    {
        if (!active || loading || saved == null || storageFailed) { return; }
        pickupTile = null;
        if (event.getMenuAction() == MenuAction.GROUND_ITEM_THIRD_OPTION && event.getMenuOption().equals("Take")
            && BagTracker.gemId(itemManager.getItemComposition(event.getId()).getName()) > 0)
        {
            pickupId = event.getId(); pickupTick = ticks;
            pickupTile = WorldPoint.fromScene(client, event.getParam0(), event.getParam1(), client.getPlane());
        }
        String target = Text.removeTags(event.getMenuTarget()).toLowerCase(java.util.Locale.ROOT);
        Item[] inventory = items(InventoryID.INV);
        Item[] bank = isBankOpen() ? items(InventoryID.BANK) : null;
        // Finish a successful earlier action before another click replaces its baseline.
        settleStorageTransfers();
        if (event.getMenuAction() == MenuAction.WIDGET_TARGET_ON_WIDGET)
        {
            Widget selected = client.getSelectedWidget();
            int selectedId = inventoryItemId(selected, inventory);
            int targetId = event.getItemId() > 0 ? event.getItemId() : inventoryItemId(event.getWidget(), inventory);
            soulBearer.use(selectedId, targetId, inventory, bank, ticks);
            int containerId = ContainerUse.container(selectedId, targetId);
            int storedId = ContainerUse.storedItem(selectedId, targetId);
            if (HerbSackTracker.isSack(containerId)) { herbSack.beginItemUse(storedId, inventory, bank, ticks); }
            for (BagTracker bag : bags)
            { if (bag.kind.owns(containerId)) { bag.beginItemUse(storedId, inventory, bank, ticks); } }
            // Do not pass "Use" through the ordinary menu path: it would cancel this transfer.
            return;
        }
        soulBearer.menu(event.getMenuOption(), event.getItemId(), inventory, bank, ticks);
        int component = event.getParam1();
        if (inventory != null && bank != null && (component == InterfaceID.Bankmain.DEPOSITCONTAINERS
            || component == InterfaceID.Bankmain.DEPOSITCONTAINERS_GRAPHIC || component == InterfaceID.Bankmain.DEPOSITINV
            || component == InterfaceID.Bankmain.DEPOSITINV_GRAPHIC))
        {
            bulkEligibleBags.clear(); bulkHerbs = false;
            for (Item item : inventory)
            {
                if (item.getQuantity() <= 0) { continue; }
                bulkHerbs |= HerbSackTracker.isSack(item.getId());
                for (BagTracker bag : bags)
                { if (bag.kind.owns(item.getId())) { bulkEligibleBags.add(bag.kind); } }
            }
            herbSack.cancelTransfer(); bags.forEach(BagTracker::cancelTransfer);
            bulkBeforeStored = BagTracker.combinedContents(herbSack, bulkHerbs, bags, bulkEligibleBags);
            bulkDeposit.begin("Empty", inventory, bank, ticks, id -> true);
            return;
        }
        if (HerbSackTracker.isSack(event.getItemId()) || target.contains("herb sack"))
        { herbSack.beginTransfer(event.getMenuOption(), inventory, bank, ticks); }
        for (BagTracker bag : bags)
        {
            if (bag.kind.owns(event.getItemId()) || target.contains(bag.kind.name)
                || (bag.kind.seeds() && WidgetUtil.componentToInterface(event.getParam1()) == InterfaceID.HOSIDIUS_SEEDBOX))
            { bag.menu(event.getMenuOption(), inventory, bank, ticks); }
        }
    }

    @Subscribe
    public void onItemDespawned(ItemDespawned event)
    { matchGemPickup(event.getItem().getId(), event.getItem().getQuantity(), event.getTile().getWorldLocation()); }

    @Subscribe
    public void onItemQuantityChanged(ItemQuantityChanged event)
    { matchGemPickup(event.getItem().getId(), event.getOldQuantity() - event.getNewQuantity(), event.getTile().getWorldLocation()); }

    private void matchGemPickup(int id, int quantity, WorldPoint tile)
    {
        if (!active || pickupTile == null || quantity <= 0 || ticks - pickupTick > 20 || id != pickupId
            || !pickupTile.equals(tile) || client.getLocalPlayer() == null
            || !tile.equals(client.getLocalPlayer().getWorldLocation())) { return; }
        pickupTile = null;
        queueGem(id, quantity);
    }

    private void queueGem(int id, long quantity)
    {
        Item[] inventory = items(InventoryID.INV);
        if (inventory == null || isBankOpen()) { return; }
        for (BagTracker bag : bags)
        {
            if (bag.canAcquire(id, inventory)) { bag.candidate(id, quantity); return; }
        }
    }

    @Subscribe
    public void onItemContainerChanged(ItemContainerChanged event)
    {
        int id = event.getContainerId();
        if (id == InventoryID.INV && active && !loading && saved != null && !storageFailed)
        {
            // Confirm remote sends before a quick bank opening replaces the cached bank.
            settleSoulBearer(items(InventoryID.INV), isBankOpen() ? items(InventoryID.BANK) : null);
        }
        if (id == InventoryID.SEED_BOX && active && !loading && saved != null && !storageFailed)
        {
            Widget seedBox = client.getWidget(InterfaceID.HosidiusSeedbox.UNIVERSE);
            if (seedBox != null && !seedBox.isHidden() && event.getItemContainer() != null)
            {
                for (BagTracker bag : bags)
                { if (bag.kind.seeds()) { bag.observeSeeds(event.getItemContainer().getItems()); } }
                checkpointExtraContainers(); dirty = true; settleUntil = ticks + 2;
            }
        }
        if (id == InventoryID.BANK || id == InventoryID.INV || id == InventoryID.WORN)
        {
            dirty = true;
            settleUntil = ticks + 2;
            if (id == InventoryID.BANK && isBankOpen() && event.getItemContainer() != null)
            {
                // Preserve the latest withdrawal/deposit even if the bank closes before the next tick.
                containers.observeBank(event.getItemContainer().getItems(), System.currentTimeMillis());
                bankTime = containers.getBankTime();
                bankSynced = true;
                checkpointBank();
            }
        }
    }

    @Subscribe
    public void onGameTick(GameTick event)
    {
        ++ticks;
        if (!active || client.getGameState() != GameState.LOGGED_IN) { return; }
        String rsProfile = configManager.getRSProfileKey();
        if (rsProfile == null) { return; }
        String key = profileKey(rsProfile);
        if (!key.equals(profile))
        {
            finishSession();
            recordLive();
            resetSession();
            profile = key;
            loadProfile(key);
        }
        if (loading || storageFailed || saved == null) { return; }
        int cannonStage = client.getVarpValue(VarPlayerID.DROPCANNON);
        boolean cannonDecayed = client.getVarbitValue(VarbitID.MCANNON_DECAYED) == 1;
        if (cannonStage != lastCannonStage || cannonDecayed != lastCannonDecayed)
        {
            lastCannonStage = cannonStage; lastCannonDecayed = cannonDecayed;
            dirty = true; settleUntil = ticks + 2;
        }
        Widget seedView = client.getWidget(InterfaceID.HosidiusSeedbox.UNIVERSE);
        if (seedView == null || seedView.isHidden()) { seedViewSince = -1; }
        else
        {
            if (seedViewSince < 0) { seedViewSince = ticks; }
            Item[] seeds = items(InventoryID.SEED_BOX);
            if (ticks >= seedViewSince + 2 && seeds != null)
            {
                for (BagTracker bag : bags)
                {
                    if (bag.kind.seeds() && bag.observeSeeds(seeds)) { dirty = true; checkpointExtraContainers(); }
                }
            }
        }
        if (herbSack.finishCheck())
        {
            dirty = true;
            checkpointHerbSack();
        }
        for (BagTracker bag : bags)
        {
            if (bag.finishCheck(ticks)) { dirty = true; checkpointExtraContainers(); }
        }
        Item[] inventoryItems = items(InventoryID.INV);
        if (inventoryItems != null)
        {
            for (BagTracker bag : bags)
            { if (bag.settleAcquisitions(inventoryItems)) { dirty = true; checkpointExtraContainers(); } }
        }
        if (ticks >= settleUntil)
        {
            settleStorageTransfers();
        }
        // Pouch consumption changes varbits without necessarily changing any item container.
        // Read after each tick's server updates; do not restart the container settling timer.
        EnumComposition runeTypes = client.getEnum(EnumID.RUNEPOUCH_RUNE);
        Map<Integer, Long> contents = RunePouchContents.read(client::getVarbitValue, runeTypes::getIntValue);
        if (!contents.equals(pouchContents)) { pouchContents = contents; dirty = true; }
        ItemContainer bank = isBankOpen() ? client.getItemContainer(InventoryID.BANK) : null;
        if (!bankSynced && bank != null)
        {
            containers.observeBank(bank.getItems(), System.currentTimeMillis());
            bankSynced = true;
            bankTime = containers.getBankTime();
            dirty = true;
            settleUntil = ticks + 2;
        }
        long now = System.currentTimeMillis();
        if (ticks >= settleUntil && (dirty || now - lastCapture >= 15_000))
        {
            capture(now);
        }
        if (live != null && containers.hasBank() && (saved.latest == null || now - saved.latest.time >= SAMPLE_INTERVAL))
        {
            recordLive();
            publish(trackingStatus());
        }
        if (now - lastSyncAttempt >= 60_000) { requestHistorySync(false); }
    }

    private void loadProfile(String key)
    {
        loading = true;
        session = new SessionTracker(System.currentTimeMillis());
        long version = generation;
        WealthStore targetStore = store;
        String rsKey = configManager.getRSProfileKey();
        publish("Loading this character's history...");
        enqueueIO(() ->
        {
            try
            {
                Filepath root = getPluginDirectory();
                Wealth.State loaded = targetStore.load(root, key).closeSession(true);
                String device = null;
                String syncNote = "History saved on this device. Not synced.";
                try
                {
                    device = targetStore.deviceId(root);
                    if (config.syncExperimental() && sessionManager.getAccountSession() != null)
                    {
                        targetStore.backupBeforeSync(root, loaded);
                        loaded = mergeHistoryCopies(loaded, rsKey, true);
                        syncNote = "History prepared for RuneLite sync. Delivery is handled by RuneLite.";
                    }
                }
                catch (IOException | IllegalArgumentException ex)
                {
                    log.debug("Unable to prepare history sync", ex);
                    syncNote = "Sync paused. Local history kept; check the client log.";
                }
                Wealth.State initial = loaded;
                String initialDevice = device, initialSyncNote = syncNote;
                clientThread.invoke(() ->
                {
                    if (!active || generation != version || !key.equals(profile)) { return; }
                    directory = root;
                    // A prior unfinished entry is closed at its last checkpoint, never merged into a new login.
                    saved = initial;
                    syncDeviceId = initialDevice; syncMessage = initialSyncNote;
                    containers.restoreBank(initial.bank);
                    containers.restoreRemoteBank(initial.extraContainers.get(SoulBearerTracker.REMOTE_BANK_KEY));
                    herbSack.restore(initial.herbSack);
                    cannon.restore(initial.extraContainers.get(CannonTracker.STORAGE_KEY));
                    for (BagTracker bag : bags) { bag.restore(initial.extraContainers.get(bag.kind.name())); }
                    bankTime = containers.getBankTime();
                    loading = false;
                    dirty = true;
                    settleUntil = ticks + 2;
                    checkpointBank();
                    persist(saved);
                    publish("Updating live inventory and equipment...");
                });
            }
            catch (IOException ex)
            {
                log.debug("Unable to load net worth history", ex);
                clientThread.invoke(() ->
                {
                    if (!active || generation != version) { return; }
                    loading = false;
                    storageFailed = true;
                    publish("Cannot read history. Original file kept; tracking paused.");
                });
            }
        });
    }

    private boolean capture(long now)
    {
        lastCapture = now;
        // Bank data is only refreshed while observed. A closed/missing bank must not block live gear updates.
        ItemContainer bank = isBankOpen() ? client.getItemContainer(InventoryID.BANK) : null;
        if (bank != null)
        {
            containers.observeBank(bank.getItems(), now);
            bankSynced = true;
            checkpointBank();
        }
        ItemContainer inventory = client.getItemContainer(InventoryID.INV);
        ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
        Map<Integer, long[]> quantities = containers.combine(
            inventory == null ? null : inventory.getItems(),
            equipment == null ? null : equipment.getItems());
        if (quantities == null) { return false; }
        cannon.observe(client.getVarpValue(VarPlayerID.DROPCANNON),
            client.getVarbitValue(VarbitID.MCANNON_DECAYED) == 1, inventory.getItems(), itemManager::canonicalize);
        checkpointExtraContainers();
        cannon.deployed().forEach((id, quantity) -> quantities.computeIfAbsent(id, ignored -> new long[3]));
        Map<Integer, Long> bankPouch = RunePouchContents.include(quantities, pouchContents);
        boolean hasSack = HerbSackTracker.location(quantities) >= 0;
        if (sackPresent && !hasSack) { herbSack.invalidate(); }
        sackPresent = hasSack;
        Map<Integer, Long> bankSack = new LinkedHashMap<>(herbSack.include(quantities));
        for (BagTracker bag : bags)
        {
            boolean present = bag.location(quantities) >= 0;
            if (!present && presentBags.remove(bag.kind)) { bag.invalidate(); }
            if (present) { presentBags.add(bag.kind); }
            bag.include(quantities).forEach((id, quantity) -> bankSack.merge(id, quantity, Long::sum));
        }
        bankTime = containers.getBankTime();
        if (itemManager.search("rune").isEmpty())
        {
            publish("Waiting for RuneLite's GE price list. Previous balance kept.");
            return false;
        }
        Map<Integer, Wealth.Entry> entries = new LinkedHashMap<>();
        Map<Integer, Long> newQuotes = new HashMap<>();
        for (Map.Entry<Integer, long[]> holding : quantities.entrySet())
        {
            int id = holding.getKey();
            ItemComposition definition = itemManager.getItemComposition(id);
            long price = quote(id);
            newQuotes.put(id, price);
            long alch = id == ItemID.COINS ? 1 : id == ItemID.PLATINUM ? 1000 : definition.getHaPrice();
            long[] q = holding.getValue();
            entries.put(id, new Wealth.Entry(id, definition.getName(), q[0], q[1], q[2], price, alch,
                definition.isMembers(), definition.isTradeable() || definition.isGeTradeable(), price == 0,
                bankPouch.getOrDefault(id, 0L), bankSack.getOrDefault(id, 0L), cannon.deployed().getOrDefault(id, 0L)));
        }
        // Sold/removed items still need TODAY'S price to attribute their interval accurately.
        if (saved.latest != null)
        {
            for (Wealth.Entry old : saved.latest.items.values())
            {
                if (!newQuotes.containsKey(old.id)) { newQuotes.put(old.id, quote(old.id)); }
            }
        }
        quotes = newQuotes;
        live = new Wealth.Snapshot(now, bankTime, entries);
        updateSession(now);
        dirty = false;
        publish(trackingStatus());
        return true;
    }

    private long quote(int id)
    {
        long price = itemManager.getItemPriceWithSource(id, false);
        // A temporarily missing quote must not invent a loss for previously priced holdings.
        if (price <= 0 && saved.latest != null)
        {
            Wealth.Entry old = saved.latest.items.get(id);
            if (old != null) { return old.price; }
        }
        return Math.max(0, price);
    }

    private boolean isBankOpen()
    {
        Widget bank = client.getWidget(InterfaceID.Bankmain.ITEMS_CONTAINER);
        return bank != null && !bank.isHidden();
    }

    private void manualSnapshot()
    {
        if (!active || !containers.hasBank() || loading || storageFailed || client.getGameState() != GameState.LOGGED_IN)
        { publish("Inventory/equipment can update now. Open your bank once to start net-worth history."); return; }
        if (ticks < settleUntil) { publish("Items are moving; wait a moment, then record."); return; }
        settleStorageTransfers();
        if (!capture(System.currentTimeMillis())) { return; }
        recordLive();
        publish("Snapshot recorded locally. " + trackingStatus());
    }

    private void recordLive()
    {
        if (saved == null || live == null || live.bankTime == 0 || !containers.hasBank() || directory == null || storageFailed) { return; }
        if (saved.latest != null && live.time <= saved.latest.time) { return; }
        saved = saved.append(live, quotes);
        persist(saved);
    }

    /** Persist observed bank changes even when a graph point is not due yet. */
    private void checkpointBank()
    {
        if (saved == null || directory == null || storageFailed || !containers.hasBank()) { return; }
        Wealth.Bank bank = containers.bankSnapshot();
        if (saved.bank != null && saved.bank.quantities.equals(bank.quantities)) { return; }
        saved = saved.withBank(bank);
        persist(saved);
    }

    private void updateSession(long now)
    {
        if (session == null || saved == null) { return; }
        Wealth.Session before = saved.activeSession;
        if (sackPresent && !herbSack.confirmed()) { session.markEstimated(); }
        if (cannon.estimated()) { session.markEstimated(); }
        boolean reconcile = herbSack.takeReconciliation() | cannon.takeReconciliation();
        for (BagTracker bag : bags)
        {
            if (presentBags.contains(bag.kind) && bag.estimated()) { session.markEstimated(); }
            reconcile |= bag.takeReconciliation();
        }
        if (soulBearerSpent > 0)
        {
            session.spend(ItemID.BLOODRUNE, soulBearerSpent, quote(ItemID.BLOODRUNE));
            session.spend(ItemID.SOULRUNE, soulBearerSpent, quote(ItemID.SOULRUNE));
        }
        session.observe(live, bankSynced, reconcile, soulBearerTransfers);
        soulBearerSpent = 0; soulBearerTransfers.clear();
        Wealth.Session current = session.current();
        saved = saved.withActiveSession(current);
        if (before == null || current.change != before.change || current.estimated != before.estimated
            || now - lastSessionSave >= 60_000)
        {
            persist(saved);
            lastSessionSave = now;
        }
    }

    private void finishSession()
    {
        if (saved != null && saved.activeSession != null && directory != null && !storageFailed)
        {
            recordLive();
            saved = saved.closeSession(false);
            persist(saved);
            exportClosedHistory(saved);
        }
        session = null;
    }

    private void checkpointHerbSack()
    {
        if (saved == null || directory == null || storageFailed || herbSack.contents() == null) { return; }
        if (herbSack.contents().equals(saved.herbSack)) { return; }
        saved = saved.withHerbSack(herbSack.contents());
        persist(saved);
    }

    private Item[] items(int container)
    {
        ItemContainer items = client.getItemContainer(container);
        return items == null ? null : items.getItems();
    }

    private static int inventoryItemId(Widget widget, Item[] inventory)
    {
        if (widget == null) { return -1; }
        return ContainerUse.inventoryItem(widget.getId(), widget.getItemId(), widget.getIndex(), inventory);
    }

    private void settleStorageTransfers()
    {
        Item[] inventory = items(InventoryID.INV);
        Item[] bank = isBankOpen() ? items(InventoryID.BANK) : null;
        settleSoulBearer(inventory, bank);
        Map<Integer, Long> bulkChanges = bulkDeposit.finish(inventory, bank, ticks, id -> true);
        if (!bulkChanges.isEmpty())
        {
            BagTracker.settleBulkDeposit(bulkChanges, bulkBeforeStored, herbSack, bulkHerbs, bags, bulkEligibleBags);
            dirty = true; checkpointHerbSack(); checkpointExtraContainers();
        }
        if (herbSack.finishTransfer(inventory, bank, ticks)) { dirty = true; checkpointHerbSack(); }
        for (BagTracker bag : bags)
        { if (bag.finishTransfer(inventory, bank, ticks)) { dirty = true; checkpointExtraContainers(); } }
    }

    private void settleSoulBearer(Item[] inventory, Item[] bank)
    {
        SoulBearerTracker.Result carried = soulBearer.finish(inventory, bank, ticks);
        if (!carried.heads.isEmpty())
        {
            containers.creditBank(carried.heads);
            soulBearerSpent += carried.spent();
            checkpointBank(); checkpointExtraContainers(); dirty = true;
        }
        if (carried.chargeTransfer != 0)
        {
            soulBearerTransfers.merge(ItemID.BLOODRUNE, carried.chargeTransfer, Long::sum);
            soulBearerTransfers.merge(ItemID.SOULRUNE, carried.chargeTransfer, Long::sum);
            dirty = true;
        }
    }

    private int resolveItem(String name)
    {
        String itemName = name.trim().replaceAll("\\.$", "");
        for (net.runelite.http.api.item.ItemPrice item : itemManager.search(itemName))
        { if (item.getName().equalsIgnoreCase(itemName)) { return item.getId(); } }
        return -1;
    }

    private void checkpointExtraContainers()
    {
        if (saved == null || directory == null || storageFailed) { return; }
        Map<String, Map<Integer, Long>> contents = new LinkedHashMap<>(saved.extraContainers);
        contents.put(SoulBearerTracker.REMOTE_BANK_KEY, containers.remoteBankSnapshot());
        contents.put(CannonTracker.STORAGE_KEY, cannon.deployed());
        for (BagTracker bag : bags)
        { if (bag.contents() != null) { contents.put(bag.kind.name(), bag.contents()); } }
        if (contents.equals(saved.extraContainers)) { return; }
        saved = saved.withExtraContainers(contents);
        persist(saved);
    }

    private void persist(Wealth.State copy)
    {
        Filepath root = directory;
        WealthStore targetStore = store;
        long version = generation;
        enqueueIO(() ->
        {
            try { targetStore.save(root, copy); }
            catch (IOException ex)
            {
                log.debug("Unable to save net worth history", ex);
                clientThread.invoke(() ->
                {
                    if (active && generation == version) { storageFailed = true; publish("History could not be saved. Tracking paused."); }
                });
            }
        });
    }

    private void publish(String status)
    {
        Wealth.Snapshot snapshot = live != null ? live : saved == null ? null : saved.latest;
        Wealth.State state = saved;
        if (inventorySessionOverlay != null)
        { inventorySessionOverlay.update(state == null ? null : state.activeSession); }
        boolean canRecord = live != null && live.bankTime > 0 && containers.hasBank() && !storageFailed;
        boolean observed = bankSynced;
        long version = generation;
        boolean syncEnabled = config.syncExperimental();
        String syncStatus = !syncEnabled ? "History saved on this device. Not synced."
            : sessionManager.getAccountSession() == null ? "History saved locally. Sign in to RuneLite to sync."
            : syncMessage;
        SwingUtilities.invokeLater(() ->
        {
            if (active && generation == version && panel != null)
            {
                panel.update(snapshot, state, canRecord, observed, status);
                panel.updateSync(syncStatus, syncEnabled);
            }
        });
    }

    private String trackingStatus()
    {
        String sackStatus = containerSyncStatus(items(InventoryID.INV), herbSack, bags);
        if (!containers.hasBank())
        { return "Inventory/equipment are live. Bank unknown; open it once to include it."
            + (sackStatus.isEmpty() ? "" : "<br>" + sackStatus); }
        if (!sackStatus.isEmpty()) { return sackStatus; }
        return bankSynced ? "Tracking bank + live inventory + equipment"
            : "Saved bank contents + live inventory/equipment. Open your bank when convenient to refresh.";
    }

    @Subscribe
    public void onConfigChanged(ConfigChanged event)
    {
        if (!WealthSync.GROUP.equals(event.getGroup())) { return; }
        if (event.getKey().equals("syncExperimental") || (event.getKey().startsWith(WealthSync.PREFIX)
            && !event.getKey().equals(WealthSync.PREFIX + syncDeviceId)))
        {
            clientThread.invoke(() ->
            {
                if (!active) { return; }
                requestHistorySync(false);
                publish(saved == null ? "Log in to load this character's history." : trackingStatus());
            });
        }
    }

    /** IO executor only. Each device writes its own key; older copies are merged, never substituted. */
    private Wealth.State mergeHistoryCopies(Wealth.State local, String rsKey, boolean importHoldings) throws IOException
    {
        List<String> keys = configManager.getRSProfileConfigurationKeys(WealthSync.GROUP, rsKey, WealthSync.PREFIX);
        if (keys.size() > 8) { throw new IOException("Too many device records for test sync"); }
        List<Wealth.State> copies = new ArrayList<>();
        for (String key : keys)
        {
            if (!key.matches(WealthSync.PREFIX + "[a-f0-9]{32}")) { throw new IOException("Invalid device record"); }
            String encoded = configManager.getConfiguration(WealthSync.GROUP, rsKey, key);
            if (encoded != null) { copies.add(WealthSync.decode(encoded, local.profile, gson)); }
        }
        return WealthSync.merge(local, copies, importHoldings);
    }

    private void requestHistorySync(boolean flush)
    {
        syncFlushRequested |= flush;
        if (!config.syncExperimental() || syncBusy || loading || saved == null || directory == null || storageFailed) { return; }
        lastSyncAttempt = System.currentTimeMillis();
        AccountSession account = sessionManager.getAccountSession();
        if (account == null || syncDeviceId == null) { return; }
        syncBusy = true;
        Wealth.State copy = saved;
        Filepath root = directory;
        String rsKey = configManager.getRSProfileKey(), device = syncDeviceId;
        long version = generation;
        enqueueIO(() ->
        {
            try
            {
                store.backupBeforeSync(root, copy);
                Wealth.State merged = mergeHistoryCopies(copy, rsKey, false);
                String encoded = WealthSync.encode(merged, gson);
                clientThread.invoke(() ->
                {
                    if (!active || generation != version || saved == null) { return; }
                    syncBusy = false;
                    if (!config.syncExperimental() || sessionManager.getAccountSession() != account) { return; }
                    if (saved.revision != copy.revision)
                    {
                        // Local loot arrived while merging. Retry from the new state instead of losing it.
                        lastSyncAttempt = 0;
                        return;
                    }
                    saved = merged;
                    persist(saved);
                    configManager.setConfiguration(WealthSync.GROUP, rsKey, WealthSync.PREFIX + device, encoded);
                    syncMessage = "History queued for RuneLite sync. Completed sessions are shared after logout.";
                    if (syncFlushRequested)
                    {
                        syncFlushRequested = false;
                        enqueueIO(configManager::sendConfig);
                    }
                    publish(trackingStatus());
                });
            }
            catch (IOException | IllegalArgumentException ex)
            {
                log.debug("Unable to sync wealth history", ex);
                clientThread.invoke(() ->
                {
                    if (!active || generation != version) { return; }
                    syncBusy = false;
                    syncMessage = "Sync paused: " + ex.getMessage() + ". Local history kept.";
                    publish(trackingStatus());
                });
            }
        });
    }

    private void syncHistoryNow()
    {
        if (!config.syncExperimental() || sessionManager.getAccountSession() == null)
        { publish("Enable cross-device sync in plugin settings and sign in to RuneLite first."); return; }
        long version = generation;
        syncMessage = "Checking RuneLite for shared history...";
        publish(saved == null ? "Log in to sync this character's history." : trackingStatus());
        // RuneLite's sendConfig also fetches changes. Never perform this network operation on the client thread.
        enqueueIO(() ->
        {
            configManager.sendConfig();
            clientThread.invoke(() -> { if (active && generation == version) { requestHistorySync(true); } });
        });
    }

    private void exportClosedHistory(Wealth.State closed)
    {
        if (!config.syncExperimental() || syncDeviceId == null || directory == null) { return; }
        AccountSession account = sessionManager.getAccountSession();
        if (account == null) { return; }
        Filepath root = directory;
        WealthStore targetStore = store;
        String rsKey = configManager.getRSProfileKey(), device = syncDeviceId;
        enqueueIO(() ->
        {
            try
            {
                if (!config.syncExperimental() || sessionManager.getAccountSession() != account) { return; }
                targetStore.backupBeforeSync(root, closed);
                Wealth.State merged = mergeHistoryCopies(closed, rsKey, false);
                String encoded = WealthSync.encode(merged, gson);
                targetStore.save(root, merged);
                if (!config.syncExperimental() || sessionManager.getAccountSession() != account) { return; }
                configManager.setConfiguration(WealthSync.GROUP, rsKey, WealthSync.PREFIX + device, encoded);
                // Flush this final session even if logout/reset changed the plugin's current profile.
                configManager.sendConfig();
            }
            catch (IOException | IllegalArgumentException ex) { log.debug("Unable to sync completed wealth session", ex); }
        });
    }

    /** Reminders concern carried containers only; banked contents remain part of valuation. */
    static String containerSyncStatus(Item[] inventory, HerbSackTracker herbs, List<BagTracker> bags)
    {
        if (inventory == null) { return ""; }
        Set<Integer> carried = new HashSet<>();
        boolean hasHerbs = false;
        for (Item item : inventory)
        {
            if (item.getQuantity() <= 0) { continue; }
            carried.add(item.getId());
            hasHerbs |= HerbSackTracker.isSack(item.getId());
        }
        StringBuilder list = new StringBuilder();
        if (hasHerbs && !herbs.confirmed()) { list.append("<br>• Herb Sack"); }
        for (BagTracker bag : bags)
        {
            if (!bag.confirmed() && (carried.contains(bag.kind.closed) || carried.contains(bag.kind.open)))
            {
                list.append("<br>•");
                for (String word : bag.kind.name.split(" "))
                { list.append(' ').append(Character.toUpperCase(word.charAt(0))).append(word.substring(1)); }
            }
        }
        return list.length() == 0 ? "" : "Check container to sync this session." + list;
    }

    /** Keep loads and atomic saves ordered, including a quick disable/re-enable. */
    private synchronized void enqueueIO(Runnable action)
    {
        ioTail = ioTail.handle((ignored, error) -> null).thenRunAsync(action, executor);
    }

    private static String profileKey(String profile)
    {
        try
        {
            byte[] bytes = MessageDigest.getInstance("SHA-256").digest(profile.getBytes(StandardCharsets.UTF_8));
            StringBuilder key = new StringBuilder();
            for (byte value : bytes) { key.append(String.format("%02x", value & 0xff)); }
            return key.toString();
        }
        catch (NoSuchAlgorithmException ex) { throw new IllegalStateException(ex); }
    }

    private static BufferedImage icon()
    {
        BufferedImage image = new BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB);
        Graphics2D g = image.createGraphics();
        g.setColor(new Color(57, 125, 255));
        g.fillRoundRect(1, 1, 30, 30, 8, 8);
        g.setColor(Color.WHITE);
        g.fillRect(7, 19, 4, 7); g.fillRect(14, 13, 4, 13); g.fillRect(21, 7, 4, 19);
        g.dispose();
        return image;
    }
}
