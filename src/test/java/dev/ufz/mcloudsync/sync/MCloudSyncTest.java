package dev.ufz.mcloudsync.sync;

import com.google.gson.Gson;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

public class MCloudSyncTest {

    @TempDir
    Path tempDir;

    private HttpServer mockCoordinator;
    private String coordinatorUrl;
    private Path serverStorageDir;
    private final Map<String, SyncCoordinatorClient.WorldStatusResponse> serverLocks = new HashMap<>();
    private final Map<String, DeltaManifest> serverManifests = new HashMap<>();
    private final Gson gson = new Gson();

    @BeforeEach
    void setUp() throws IOException {
        serverStorageDir = tempDir.resolve("coordinator_storage");
        Files.createDirectories(serverStorageDir);

        mockCoordinator = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        int port = mockCoordinator.getAddress().getPort();
        coordinatorUrl = "http://127.0.0.1:" + port;

        mockCoordinator.createContext("/api/v1/health", exchange -> {
            sendJson(exchange, 200, Map.of("status", "ok"));
        });

        mockCoordinator.createContext("/api/v1/worlds/", this::handleWorldRoute);
        mockCoordinator.start();
    }

    @AfterEach
    void tearDown() {
        if (mockCoordinator != null) {
            mockCoordinator.stop(0);
        }
    }

    @Test
    @DisplayName("1. InviteToken encodes and decodes WorldSyncMetadata accurately")
    void testInviteTokenRoundtrip() throws IOException {
        Path worldDir = tempDir.resolve("world_invite_test");
        WorldSyncMetadata original = new WorldSyncMetadata(
                "pokemon-world-001",
                "Cobbleverse Adventure",
                "https://sync-test.trycloudflare.com/",
                "secret-token-xyz"
        );
        original.save(worldDir);

        assertTrue(WorldSyncMetadata.isSyncedWorld(worldDir));

        String inviteToken = original.toInviteToken();
        assertTrue(inviteToken.startsWith("mcloudsync://"));

        WorldSyncMetadata decoded = WorldSyncMetadata.fromInviteToken(inviteToken);
        assertEquals("pokemon-world-001", decoded.worldId);
        assertEquals("Cobbleverse Adventure", decoded.worldName);
        assertEquals("https://sync-test.trycloudflare.com", decoded.coordinatorUrl);
        assertEquals("secret-token-xyz", decoded.authToken);
    }

    @Test
    @DisplayName("2. DeltaManifest ignores ephemeral lock/temp files and computes exact diffs")
    void testDeltaManifestAndDiff() throws IOException {
        Path dirA = tempDir.resolve("world_A");
        Files.createDirectories(dirA.resolve("region"));
        Files.createDirectories(dirA.resolve("cobblemonplayerdata"));

        Files.writeString(dirA.resolve("region/r.0.0.mca"), "chunk-data-v1");
        Files.writeString(dirA.resolve("region/r.0.1.mca"), "unchanged-chunk");
        Files.writeString(dirA.resolve("cobblemonplayerdata/player1.json"), "{\"party\":[\"pikachu\"]}");
        // Ephemeral files that MUST be ignored
        Files.writeString(dirA.resolve("session.lock"), "lock");
        Files.writeString(dirA.resolve(".mcloud-sync.json"), "{}");
        Files.writeString(dirA.resolve(".mcloud-local.lock"), "{}");
        Files.writeString(dirA.resolve("level.dat_old"), "backup");

        DeltaManifest manifest1 = DeltaManifest.buildFromDirectory(dirA, 1L);
        assertEquals(3, manifest1.files.size());
        assertFalse(manifest1.files.containsKey("session.lock"));
        assertFalse(manifest1.files.containsKey("level.dat_old"));

        // Simulate Player A modifying r.0.0.mca, adding r.1.1.mca, and deleting r.0.1.mca
        Path dirB = tempDir.resolve("world_B");
        Files.createDirectories(dirB.resolve("region"));
        Files.createDirectories(dirB.resolve("cobblemonplayerdata"));
        Files.writeString(dirB.resolve("region/r.0.0.mca"), "chunk-data-v2-modified");
        Files.writeString(dirB.resolve("region/r.1.1.mca"), "brand-new-region");
        Files.writeString(dirB.resolve("cobblemonplayerdata/player1.json"), "{\"party\":[\"pikachu\"]}");

        DeltaManifest manifest2 = DeltaManifest.buildFromDirectory(dirB, 2L);
        DeltaManifest.DiffResult diff = DeltaManifest.diff(manifest2, manifest1);

        assertEquals(List.of("region/r.0.0.mca", "region/r.1.1.mca"), diff.toTransfer());
        assertEquals(List.of("region/r.0.1.mca"), diff.toDelete());
    }

    @Test
    @DisplayName("3. LevelDatPlayerSwapper isolates Player A and Player B inventories in Singleplayer level.dat")
    void testLevelDatPlayerSwapperPreventsInventoryCloning() throws IOException {
        Path worldDir = tempDir.resolve("shared_pokemon_world");
        Files.createDirectories(worldDir);

        UUID uuidUriel = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID uuidFriend = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // Create initial level.dat where Uriel played in Singleplayer (Health = 20, XpLevel = 42)
        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag urielPlayerTag = new CompoundTag();
        urielPlayerTag.putUUID("UUID", uuidUriel);
        urielPlayerTag.putInt("XpLevel", 42);
        urielPlayerTag.putString("CustomInventoryMarker", "Uriel_MasterBall");
        data.put("Player", urielPlayerTag);
        data.putString("LevelName", "Cobbleverse");
        root.put("Data", data);
        NbtIo.writeCompressed(root, worldDir.resolve("level.dat"));

        // 1. Uriel closes the game -> normalizeForUpload extracts Uriel's tag to playerdata/<uuidUriel>.dat and strips Data.Player
        assertTrue(LevelDatPlayerSwapper.normalizeForUpload(worldDir, uuidUriel));
        assertTrue(Files.exists(worldDir.resolve("playerdata/" + uuidUriel + ".dat")));

        CompoundTag normalizedRoot = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        assertFalse(normalizedRoot.getCompound("Data").contains("Player"),
                "Data.Player must be stripped on upload so the next player does not clone Uriel's body");

        // 2. Friend opens the world for the FIRST time -> prepareForLocalPlayer leaves Data.Player empty so Friend spawns fresh
        assertTrue(LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, uuidFriend));
        CompoundTag friendFirstLoad = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        assertFalse(friendFirstLoad.getCompound("Data").contains("Player"),
                "New player must not inherit Uriel's Data.Player");

        // 3. Friend plays, gets XpLevel = 15 & GreatBall, and closes the game
        CompoundTag friendPlayedRoot = friendFirstLoad.copy();
        CompoundTag friendPlayerTag = new CompoundTag();
        friendPlayerTag.putUUID("UUID", uuidFriend);
        friendPlayerTag.putInt("XpLevel", 15);
        friendPlayerTag.putString("CustomInventoryMarker", "Friend_GreatBall");
        friendPlayedRoot.getCompound("Data").put("Player", friendPlayerTag);
        NbtIo.writeCompressed(friendPlayedRoot, worldDir.resolve("level.dat"));

        assertTrue(LevelDatPlayerSwapper.normalizeForUpload(worldDir, uuidFriend));
        assertTrue(Files.exists(worldDir.resolve("playerdata/" + uuidFriend + ".dat")));

        // 4. Uriel opens the world again -> prepareForLocalPlayer restores Uriel's MasterBall & XpLevel 42 into Data.Player!
        assertTrue(LevelDatPlayerSwapper.prepareForLocalPlayer(worldDir, uuidUriel));
        CompoundTag urielReloaded = NbtIo.readCompressed(worldDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag activePlayer = urielReloaded.getCompound("Data").getCompound("Player");
        assertEquals(42, activePlayer.getInt("XpLevel"));
        assertEquals("Uriel_MasterBall", activePlayer.getString("CustomInventoryMarker"));
        assertEquals(uuidUriel, activePlayer.getUUID("UUID"));
    }

    @Test
    @DisplayName("4. Full End-to-End Orchestrator: Uriel pushes world -> Friend clones & pulls -> Mutex blocks simultaneous Singleplayer & triggers LAN handoff")
    void testEndToEndWorldSyncAndMutex() throws IOException, InterruptedException {
        UUID uuidUriel = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID uuidFriend = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // Step A: Uriel creates a world and initializes MCloudSync
        Path urielWorldDir = tempDir.resolve("uriel_saves/PokemonWorld");
        Files.createDirectories(urielWorldDir.resolve("region"));
        Files.writeString(urielWorldDir.resolve("region/r.0.0.mca"), "region-zero-initial");

        CompoundTag root = new CompoundTag();
        CompoundTag data = new CompoundTag();
        CompoundTag urielTag = new CompoundTag();
        urielTag.putUUID("UUID", uuidUriel);
        urielTag.putInt("XpLevel", 99);
        data.put("Player", urielTag);
        root.put("Data", data);
        NbtIo.writeCompressed(root, urielWorldDir.resolve("level.dat"));

        WorldSyncMetadata urielMeta = new WorldSyncMetadata("shared-poke-1", "PokemonWorld", coordinatorUrl, "test-token");
        urielMeta.save(urielWorldDir);

        // Uriel launches & exits world -> pushes revision 1 and releases lock
        WorldSyncOrchestrator.SyncSessionResult urielLaunch = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                urielWorldDir, uuidUriel, "Uriel", null
        );
        assertTrue(urielLaunch.canPlaySingleplayer());
        assertTrue(LocalCrashJournal.hasDirtyCrashJournal(urielWorldDir));

        // While Uriel is still in-game (lock held), Friend tries to open the world in Singleplayer
        Path friendSavesDir = tempDir.resolve("friend_saves");
        String inviteToken = urielMeta.toInviteToken();
        Path friendWorldDir = WorldSyncOrchestrator.cloneWorldFromInviteToken(
                friendSavesDir, "FriendPokemonWorld", inviteToken, uuidFriend, null
        );

        WorldSyncOrchestrator.SyncSessionResult friendBlockedAttempt = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                friendWorldDir, uuidFriend, "Amigo", null
        );
        assertFalse(friendBlockedAttempt.canPlaySingleplayer(), "Mutex must block Friend while Uriel holds the lock");
        assertEquals("Uriel", friendBlockedAttempt.lockedByPlayerName());

        // Friend sends a Join Request to Uriel, and Uriel opens LAN via MCloud
        SyncCoordinatorClient client = new SyncCoordinatorClient(coordinatorUrl, "test-token");
        client.requestJoinLan("shared-poke-1", uuidFriend.toString(), "Amigo");
        SyncCoordinatorClient.WorldStatusResponse hbStatus = client.sendHeartbeat(
                "shared-poke-1", uuidUriel.toString(), "poke-tunnel-123.trycloudflare.com"
        );
        assertEquals(1, hbStatus.joinRequests.size());
        assertEquals("Amigo", hbStatus.joinRequests.get(0).requesterName);

        // Now Friend tries again and sees the active MCloud LAN tunnel URL!
        WorldSyncOrchestrator.SyncSessionResult friendLanAttempt = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                friendWorldDir, uuidFriend, "Amigo", null
        );
        assertFalse(friendLanAttempt.canPlaySingleplayer());
        assertTrue(friendLanAttempt.shouldConnectLan());
        assertEquals("poke-tunnel-123.trycloudflare.com", friendLanAttempt.lanTunnelUrl());

        // Step B: Uriel finishes playing, modifies r.0.0.mca, and exits cleanly
        Files.writeString(urielWorldDir.resolve("region/r.0.0.mca"), "region-zero-updated-by-uriel");
        WorldSyncOrchestrator.finalizeWorldAfterExit(urielWorldDir, uuidUriel, null);
        assertFalse(LocalCrashJournal.hasDirtyCrashJournal(urielWorldDir));

        // Step C: Friend now opens the world in Singleplayer -> acquires lock and downloads Uriel's updated r.0.0.mca!
        WorldSyncOrchestrator.SyncSessionResult friendSuccessLaunch = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                friendWorldDir, uuidFriend, "Amigo", null
        );
        assertTrue(friendSuccessLaunch.canPlaySingleplayer());
        assertEquals("region-zero-updated-by-uriel", Files.readString(friendWorldDir.resolve("region/r.0.0.mca")));

        WorldSyncOrchestrator.finalizeWorldAfterExit(friendWorldDir, uuidFriend, null);
    }

    @Test
    @DisplayName("5. Crash Recovery Journal: dirty .mcloud-local.lock triggers automatic recovery upload before next session")
    void testCrashRecoveryJournal() throws IOException, InterruptedException {
        UUID uuidUriel = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID uuidFriend = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        Path urielWorldDir = tempDir.resolve("crash_test/PokemonCrashWorld");
        Files.createDirectories(urielWorldDir.resolve("region"));
        Files.writeString(urielWorldDir.resolve("region/r.0.0.mca"), "before-crash-data");

        WorldSyncMetadata meta = new WorldSyncMetadata("crash-world-1", "PokemonCrashWorld", coordinatorUrl, "test-token");
        meta.save(urielWorldDir);

        // Simulate Uriel starting a session and modifying a region, then crashing (finalizeWorldAfterExit is NEVER called)
        WorldSyncOrchestrator.prepareWorldBeforeLaunch(urielWorldDir, uuidUriel, "Uriel", null);
        assertTrue(LocalCrashJournal.hasDirtyCrashJournal(urielWorldDir));
        Files.writeString(urielWorldDir.resolve("region/r.0.0.mca"), "recovered-after-crash-data");

        // Uriel restarts Minecraft and launches the world -> prepareWorldBeforeLaunch detects dirty journal and pushes recovery
        WorldSyncOrchestrator.prepareWorldBeforeLaunch(urielWorldDir, uuidUriel, "Uriel", null);
        WorldSyncOrchestrator.finalizeWorldAfterExit(urielWorldDir, uuidUriel, null);

        // Verify the coordinator received the recovered data by cloning as Friend
        Path friendWorldDir = WorldSyncOrchestrator.cloneWorldFromInviteToken(
                tempDir.resolve("crash_friend"), "FriendCrashWorld", meta.toInviteToken(), uuidFriend, null
        );
        assertEquals("recovered-after-crash-data", Files.readString(friendWorldDir.resolve("region/r.0.0.mca")));
    }

    // --- Lightweight Mock Coordinator Handler for JUnit Integration Test ---

    private void handleWorldRoute(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String prefix = "/api/v1/worlds/";
        String remainder = path.substring(prefix.length());
        int slashIdx = remainder.indexOf('/');
        String worldId = URLDecoder.decode(remainder.substring(0, slashIdx), StandardCharsets.UTF_8);
        String action = remainder.substring(slashIdx + 1);

        DeltaManifest manifest = serverManifests.computeIfAbsent(worldId, k -> new DeltaManifest(0L, new HashMap<>()));
        SyncCoordinatorClient.WorldStatusResponse lock = serverLocks.computeIfAbsent(worldId, k -> {
            SyncCoordinatorClient.WorldStatusResponse s = new SyncCoordinatorClient.WorldStatusResponse();
            s.worldId = k;
            s.state = SyncCoordinatorClient.LockState.FREE;
            return s;
        });
        lock.revision = manifest.revision;

        String method = exchange.getRequestMethod();
        if ("GET".equals(method) && "status".equals(action)) {
            sendJson(exchange, 200, lock);
            return;
        }
        if ("POST".equals(method) && "lock".equals(action)) {
            Map<?, ?> body = readBodyMap(exchange);
            String holderUuid = (String) body.get("holderUuid");
            String holderName = (String) body.get("holderName");
            if (lock.state == SyncCoordinatorClient.LockState.LOCKED && !holderUuid.equals(lock.holderUuid)) {
                sendJson(exchange, 409, lock);
                return;
            }
            lock.state = SyncCoordinatorClient.LockState.LOCKED;
            lock.holderUuid = holderUuid;
            lock.holderName = holderName;
            lock.acquiredAt = System.currentTimeMillis();
            lock.lastHeartbeatAt = System.currentTimeMillis();
            lock.lanTunnelUrl = null;
            lock.joinRequests = new ArrayList<>();
            sendJson(exchange, 200, lock);
            return;
        }
        if ("POST".equals(method) && "heartbeat".equals(action)) {
            Map<?, ?> body = readBodyMap(exchange);
            lock.lastHeartbeatAt = System.currentTimeMillis();
            if (body.containsKey("lanTunnelUrl")) {
                lock.lanTunnelUrl = (String) body.get("lanTunnelUrl");
            }
            sendJson(exchange, 200, lock);
            return;
        }
        if ("POST".equals(method) && "unlock".equals(action)) {
            lock.state = SyncCoordinatorClient.LockState.FREE;
            lock.holderUuid = null;
            lock.holderName = null;
            lock.lanTunnelUrl = null;
            lock.joinRequests = new ArrayList<>();
            sendJson(exchange, 200, lock);
            return;
        }
        if ("POST".equals(method) && "join-request".equals(action)) {
            Map<?, ?> body = readBodyMap(exchange);
            lock.joinRequests.add(new SyncCoordinatorClient.JoinRequestInfo(
                    (String) body.get("requesterUuid"),
                    (String) body.get("requesterName"),
                    System.currentTimeMillis()
            ));
            sendJson(exchange, 200, lock);
            return;
        }
        if ("GET".equals(method) && "manifest".equals(action)) {
            sendJson(exchange, 200, manifest);
            return;
        }
        if (action.startsWith("files/")) {
            String relPath = URLDecoder.decode(action.substring("files/".length()), StandardCharsets.UTF_8);
            Path targetFile = serverStorageDir.resolve(worldId).resolve(relPath);
            if ("PUT".equals(method)) {
                Files.createDirectories(targetFile.getParent());
                try (InputStream in = exchange.getRequestBody()) {
                    Files.copy(in, targetFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                }
                sendJson(exchange, 200, Map.of("saved", relPath));
                return;
            }
            if ("GET".equals(method)) {
                byte[] bytes = Files.readAllBytes(targetFile);
                exchange.sendResponseHeaders(200, bytes.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(bytes);
                }
                return;
            }
        }
        if ("POST".equals(method) && "commit".equals(action)) {
            String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            com.google.gson.JsonObject obj = gson.fromJson(raw, com.google.gson.JsonObject.class);
            DeltaManifest committed = DeltaManifest.fromJson(obj.get("manifest").toString());
            committed.revision = manifest.revision + 1;
            serverManifests.put(worldId, committed);
            lock.revision = committed.revision;
            sendJson(exchange, 200, committed);
            return;
        }
        sendJson(exchange, 404, Map.of("error", "not found"));
    }

    private Map<?, ?> readBodyMap(HttpExchange exchange) throws IOException {
        String raw = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        return gson.fromJson(raw, Map.class);
    }

    private void sendJson(HttpExchange exchange, int code, Object payload) throws IOException {
        byte[] bytes = gson.toJson(payload).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }
}
