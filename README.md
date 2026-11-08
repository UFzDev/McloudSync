# MCloud Sync (`mcloudsync`)

**P2P/Coordinator World Mutex, SHA-256 Delta Region Synchronization, and Singleplayer Inventory Isolation for [MCloud](https://github.com/UFzDev/Mcloud).**

Share heavy modded (or Vanilla) Minecraft worlds with friends **without paying for or hosting a 24/7 dedicated Minecraft server**.

---

## Why MCloud Sync Exists

Running a 150+ mod pack (or large survival world) on a 24/7 dedicated server requires a modern high-frequency CPU and 6–10 GB of RAM. Low-power home servers (or free cloud tiers) cannot tick heavy modded worlds without severe TPS lag.

**MCloud Sync** turns your Singleplayer save into a shared cloud/P2P world:
- **When you want to play solo**: You open the world in Singleplayer on your gaming PC. MCloud Sync acquires a **Session Mutex Lock**, downloads only the modified region/data files (**SHA-256 Delta Sync**), and swaps your UUID into `level.dat` so you keep your own inventory, coordinates, and player progress.
- **When your friend wants to play solo later**: When you exit, MCloud Sync uploads only the changed `.mca`/`.dat` files in 2 seconds and releases the lock. Your friend can then open the exact same world on their PC whenever they want.
- **When you both play at the same time**: If your friend tries to open the world while you are playing, MCloud Sync prevents world corruption by showing a **"World In Use"** screen with a 1-click **[Ask Friend to Open LAN]** button. You receive a native Minecraft Toast notification, click **[OPEN LAN WITH MCLOUD]**, and your friend automatically connects to your PC over MCloud's zero-config Cloudflare WebSocket tunnel!

---

## Core Engineering Features

1. **100% Companion to MCloud (`mcloud`)**:
   - Depends directly on `mcloud` (`>=1.1.0`) for zero-port-forwarding Cloudflare WebSocket LAN tunnels.
2. **Strict Opt-In Per World (`.mcloud-sync.json`)**:
   - Your regular Singleplayer worlds are never touched. Only worlds explicitly initialized with `/mcloudsync init` or cloned via **Link MCloud World** are synchronized.
3. **Singleplayer Cloned-Inventory Prevention (`LevelDatPlayerSwapper`)**:
   - Solves Minecraft's `level.dat -> Data.Player` singleplayer trap. Every player's inventory, position, XP, and modded player data (`playerdata/<uuid>.dat`, etc.) are strictly isolated by UUID.
4. **SHA-256 Delta Region Sync (Cloudflare 100 MB Limit Safe)**:
   - Never uploads monolithic `.zip` archives. Only modified `.mca` regions and `.dat` files are transferred, bypassing Cloudflare's HTTP 413 100 MB request limit and closing the game in seconds.
5. **Crash & Power-Outage Recovery (`.mcloud-local.lock`)**:
   - Protects against zombie locks and unsaved progress if Minecraft crashes (`Exit Code 1`).

---

## Quick Start Guide

### 1. Start the Ultra-Lightweight Coordinator (<25 MB RAM)
You can run the coordinator 24/7 on any old PC, home Linux server (like an Acer Pentium box), Raspberry Pi, or free VPS using Docker:

```bash
git clone https://github.com/UFzDev/McloudSync.git
cd McloudSync
docker compose up -d
```

Check your public Cloudflare Tunnel URL for the coordinator:
```bash
docker logs mcloudsync-tunnel
```
*(Or run directly without Docker using Node.js 20+: `node coordinator/server.mjs`)*

### 2. Host Player: Enable Sync on Your World
1. Install both **`mcloud`** and **`mcloudsync`** in your `mods/` folder.
2. Open your Singleplayer world and run:
   ```text
   /mcloudsync init https://your-coordinator-url.trycloudflare.com
   ```
3. Click **[COPY INVITE TOKEN]** in chat and send the `mcloudsync://...` token to your friend on Discord/WhatsApp.
4. When you Save & Quit (or run `/mcloudsync push`), your world delta is uploaded.

### 3. Friend: Link the Shared World (First Time)
1. Install both **`mcloud`** and **`mcloudsync`** in `mods/`.
2. Go to **Singleplayer** -> click **Link MCloud World** (top-right corner).
3. Paste the `mcloudsync://...` Invite Token and click **Download & Link World**.
4. The world now appears in your Singleplayer list!

---

## In-Game Commands

- `/mcloudsync status` — Check sync status, World ID, Coordinator URL, and current revision.
- `/mcloudsync init <coordinatorUrl> [authToken]` — Enable MCloud Sync for the current Singleplayer world.
- `/mcloudsync invite` — Generate and copy the `mcloudsync://...` Invite Token for your friends.
- `/mcloudsync push` — Force an immediate background delta upload while playing.
- `/mcloudsync openlan` — Open the current synced world to LAN via MCloud and notify waiting friends.

---

## Building from Source

```bash
./gradlew build
```

---

## License

MIT License — Created by **[UFzDev](https://github.com/UFzDev)**.
