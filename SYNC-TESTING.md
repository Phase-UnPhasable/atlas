# Experimental cross-device history sync

Enable **Cross-device sync (test)** in plugin settings on both devices, sign into the same RuneLite account and OSRS character, then use **Sync history now** in the sidebar. Enable it before character login on the receiving device to import the saved bank/container checkpoint. When enabled after login, history imports while the running client keeps its own live bank cache; relog to restore a remote checkpoint.

RuneLite transports the data through its character-scoped configuration store, under the `atlas` group. Local files use `.runelite/plugin-data/atlas`. No Atlas account or external service is used. Each device generates a random local ID via Filepath and writes a separate `historyV1_<id>` record. Do not copy `sync-device-id.txt` between devices.

Records contain a bounded, compressed representation of the latest snapshot, bank and known container checkpoints, graph points and completed sessions. Item labels are not transmitted; the client resolves names from its item cache on the next live capture. Running sessions remain local until logout or recovery; a remote payload cannot replace a current session. The merge deduplicates sessions by login timestamp and retains the latest end/result for a matching session. Graph observations are merged by timestamp. An offline branch without a reliable preceding market observation retains absolute net worth but marks the affected market/item split unavailable. On character load the newest bank-observation timestamp wins; incoming history during play does not overwrite the running bank cache.

A Filepath-managed `.before-sync.json` backup is created once per character before the first enabled merge. The ordinary local history remains the working archive. IO, compression, merging and RuneLite's blocking configuration flush run on the shared executor; cloud results are applied on the client thread only if the character and local revision still match. New loot arriving during a merge causes a retry rather than replacing it with stale data. Logout/client close queues and requests a native configuration flush. There is no per-record delivery acknowledgement in the API used here: the UI reports **queued**, never a verified successful upload.

Prototype limits: 32,000 Base64 characters per device record, eight device records per character, and 4 MB decompressed per payload. These are conservative implementation budgets, **not verified RuneLite server quotas**. Oversized/corrupt/unsupported records pause syncing without trimming local history. This is not unlimited cloud archival; a larger storage design may be needed for long histories. Native-service acceptance, performance and suitability must be validated before public submission. Disabling the option stops further plugin sync reads/writes but does not erase data already stored/queued through RuneLite.

Manual acceptance:

1. PC → Deck → PC: completed sessions and recorded graph points appear once on both devices; the current login has its own baseline and inventory/equipment values.
2. Repeated sync and a stale device reconnect do not delete or duplicate completed sessions or newer observations.
3. Offline sessions merge after reconnect. Net worth remains visible; an uncertain market/item interval is explicitly unavailable.
4. An incoming bank checkpoint cannot invent session profit, replace a bank already being used by the running client, or duplicate Soul Bearer remote deposits.
5. Different OSRS characters/modes remain separate; malformed payloads and budget overruns preserve local files and pre-sync backups.
6. Logout/normal close completes the native flush before switching. Forced Steam shutdown and suspension need separate real-device testing; queued is not proof of delivery.

112 automated checks cover codec bounds, representative payload size, PC/Deck merge round-trips, offline forks, duplicate suppression, account/mode scope validation and current-session preservation. The sync sidebar was rendered offline. No game input is automated.

Real-device confirmation on October 19, 2026: the user launched the development client on Steam Deck, logged into the character, and saw the PC's completed sessions. Drinking one prayer-potion dose on Deck recorded approximately -2.4K GP. After normal client close and reopening on PC, that saved Deck session appeared automatically without pressing the sync button. This confirms that round trip for the tested account and payload; it does not establish long-term service quotas, offline/concurrent behavior, or forced shutdown reliability. Steam shortcut troubleshooting is outside the current task.
