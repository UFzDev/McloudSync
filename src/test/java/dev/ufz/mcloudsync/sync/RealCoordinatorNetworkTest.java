package dev.ufz.mcloudsync.sync;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtAccounter;
import net.minecraft.nbt.NbtIo;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * End-to-End Live Network Integration Test suite that spawns the real Node.js
 * `coordinator/server.mjs` coordinator server as a live subprocess and validates
 * the complete network flow, HTTP contract, mutex locking, stale lock TTL takeover,
 * multi-megabyte SHA-256 delta streaming, path traversal security, and MCloud LAN tunneling.
 */
public class RealCoordinatorNetworkTest {

    private static final String AUTH_TOKEN = "test-secret-bearer-token-12345";
    private static Path serverDataDir;
    private static Process coordinatorProcess;
    private static int serverPort;
    private static String baseUrl;
    private static SyncCoordinatorClient client;

    @BeforeAll
    static void startCoordinatorServer(@TempDir Path globalTemp) throws Exception {
        serverDataDir = globalTemp.resolve("coordinator_live_data");
        Files.createDirectories(serverDataDir);

        serverPort = findFreePort();
        baseUrl = "http://127.0.0.1:" + serverPort;

        Path projectDir = Path.of("").toAbsolutePath();
        Path serverMjs = projectDir.resolve("coordinator/server.mjs");
        if (!Files.exists(serverMjs)) {
            serverMjs = Path.of("D:/Usuario/Documentos/ProyectosPersonales/McloudSync/coordinator/server.mjs");
        }
        assertTrue(Files.exists(serverMjs), "coordinator/server.mjs must exist at " + serverMjs);

        ProcessBuilder pb = new ProcessBuilder("node", serverMjs.toAbsolutePath().toString());
        pb.environment().put("PORT", String.valueOf(serverPort));
        pb.environment().put("DATA_DIR", serverDataDir.toAbsolutePath().toString());
        pb.environment().put("AUTH_TOKEN", AUTH_TOKEN);
        pb.environment().put("LOCK_TTL_MS", "1000"); // 1 second TTL for test environment
        pb.redirectErrorStream(true);

        coordinatorProcess = pb.start();

        // Pipe server stdout/stderr to prevent blocking on full OS pipe buffers
        Thread logReader = new Thread(() -> {
            try (BufferedReader r = new BufferedReader(new InputStreamReader(coordinatorProcess.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[Coordinator-Node] " + line);
                }
            } catch (IOException ignored) {
            }
        }, "CoordinatorProcessLogger");
        logReader.setDaemon(true);
        logReader.start();

        client = new SyncCoordinatorClient(baseUrl, AUTH_TOKEN);

        // Wait until coordinator responds to health checks (max 10 seconds)
        HttpClient checkClient = HttpClient.newBuilder().connectTimeout(Duration.ofMillis(500)).build();
        boolean ready = false;
        long deadline = System.currentTimeMillis() + 10000;
        while (System.currentTimeMillis() < deadline) {
            if (!coordinatorProcess.isAlive()) {
                fail("Node.js coordinator process terminated prematurely with exit code: " + coordinatorProcess.exitValue());
            }
            try {
                HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/health")).GET().build();
                HttpResponse<String> resp = checkClient.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                if (resp.statusCode() == 200) {
                    ready = true;
                    break;
                }
            } catch (Exception ignored) {
            }
            Thread.sleep(100);
        }
        assertTrue(ready, "Node.js coordinator server failed to respond within 10 seconds at " + baseUrl);
    }

    @AfterAll
    static void stopCoordinatorServer() throws Exception {
        if (coordinatorProcess != null && coordinatorProcess.isAlive()) {
            coordinatorProcess.destroyForcibly();
            coordinatorProcess.waitFor(5, TimeUnit.SECONDS);
        }
    }

    private static int findFreePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        }
    }

    @Test
    @DisplayName("1. Health check (GET /api/v1/health) succeeds without credentials and reports coordinator status")
    void testHealthCheck() throws Exception {
        assertTrue(client.checkHealth(), "SyncCoordinatorClient.checkHealth() must return true against live coordinator");

        // Direct HTTP call without any Authorization header to ensure public reachability for health checkers
        HttpClient rawHttp = HttpClient.newHttpClient();
        HttpRequest req = HttpRequest.newBuilder().uri(URI.create(baseUrl + "/api/v1/health")).GET().build();
        HttpResponse<String> resp = rawHttp.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));

        assertEquals(200, resp.statusCode());
        assertTrue(resp.body().contains("\"status\":\"ok\""), "Response must contain status ok: " + resp.body());
        assertTrue(resp.body().contains("mcloudsync-coordinator"), "Response must contain service identifier: " + resp.body());
    }

    @Test
    @DisplayName("2. Unauthorized rejection (HTTP 401) occurs when Bearer token is missing or incorrect")
    void testUnauthorizedRejection() {
        SyncCoordinatorClient wrongTokenClient = new SyncCoordinatorClient(baseUrl, "invalid-token-xyz");
        IOException exWrong = assertThrows(IOException.class, () -> wrongTokenClient.getWorldStatus("unauthorized-world"));
        assertTrue(exWrong.getMessage().contains("401"), "Expected HTTP 401 on wrong token, got: " + exWrong.getMessage());

        SyncCoordinatorClient emptyTokenClient = new SyncCoordinatorClient(baseUrl, "");
        IOException exEmpty = assertThrows(IOException.class, () -> emptyTokenClient.getWorldStatus("unauthorized-world"));
        assertTrue(exEmpty.getMessage().contains("401"), "Expected HTTP 401 on missing token, got: " + exEmpty.getMessage());
    }

    @Test
    @DisplayName("3. Mutex lock acquisition, HTTP 409 conflict, heartbeat renewal, and automatic stale lock TTL takeover after crash")
    void testMutexLockConflictHeartbeatAndStaleTtlTakeover() throws Exception {
        String worldId = "mutex-world-" + UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        // 1. Player A acquires the lock
        SyncCoordinatorClient.LockAcquireResult resA = client.acquireLock(worldId, playerA.toString(), "PlayerA", true);
        assertTrue(resA.acquired(), "Player A should successfully acquire free lock");
        assertEquals(SyncCoordinatorClient.LockState.LOCKED, resA.status().state);
        assertEquals("PlayerA", resA.status().holderName);

        // 2. Player B tries to acquire the lock -> HTTP 409 Conflict
        SyncCoordinatorClient.LockAcquireResult resB = client.acquireLock(worldId, playerB.toString(), "PlayerB", true);
        assertFalse(resB.acquired(), "Player B must be rejected while Player A holds the lock");
        assertEquals("PlayerA", resB.status().holderName);
        assertTrue(resB.message().contains("PlayerA"), "Conflict message should cite current holder");

        // 3. Player A renews lock via periodic Heartbeat
        SyncCoordinatorClient.WorldStatusResponse hbResp = client.sendHeartbeat(worldId, playerA.toString(), null);
        assertEquals(SyncCoordinatorClient.LockState.LOCKED, hbResp.state);
        assertEquals(playerA.toString(), hbResp.holderUuid);

        // 4. Player B tries heartbeat -> HTTP 409 Conflict (only holder can heartbeat)
        assertThrows(IOException.class, () -> client.sendHeartbeat(worldId, playerB.toString(), null),
                "Heartbeat from non-holder must fail with HTTP 409");

        // 5. Simulate Player A crash: Player A stops ticking.
        // Wait 1300ms (> LOCK_TTL_MS=1000) so lock transitions to STALE
        Thread.sleep(1300);

        SyncCoordinatorClient.WorldStatusResponse staleStatus = client.getWorldStatus(worldId);
        assertEquals(SyncCoordinatorClient.LockState.STALE, staleStatus.state, "Lock must transition to STALE after TTL expires");

        // 6a. Player B calls acquireLock with forceTakeoverIfStale = false:
        // Must reject with acquired = false, status != null, status.state == STALE (triggers the client disconnect warning screen!)
        SyncCoordinatorClient.LockAcquireResult staleWarningRes = client.acquireLock(worldId, playerB.toString(), "PlayerB", false);
        assertFalse(staleWarningRes.acquired(), "Player B must not acquire stale lock when forceTakeoverIfStale = false");
        assertNotNull(staleWarningRes.status());
        assertEquals(SyncCoordinatorClient.LockState.STALE, staleWarningRes.status().state);
        assertEquals("PlayerA", staleWarningRes.status().holderName);

        // 6b. Player B explicitly takes over the stale lock with forceTakeoverIfStale = true
        SyncCoordinatorClient.LockAcquireResult takeoverRes = client.acquireLock(worldId, playerB.toString(), "PlayerB", true);
        assertTrue(takeoverRes.acquired(), "Player B should successfully take over the stale lock with forceTakeoverIfStale = true");
        assertEquals(playerB.toString(), takeoverRes.status().holderUuid);
        assertEquals(SyncCoordinatorClient.LockState.LOCKED, takeoverRes.status().state);

        // 7. Player B cleanly releases the lock
        SyncCoordinatorClient.WorldStatusResponse freeStatus = client.releaseLock(worldId, playerB.toString());
        assertEquals(SyncCoordinatorClient.LockState.FREE, freeStatus.state);
        assertNull(freeStatus.holderUuid);
    }

    @Test
    @DisplayName("4. Binary file upload with SHA-256 verification and HTTP 400 rejection on corrupt hash")
    void testBinaryFileUploadWithSha256VerificationAndRejection(@TempDir Path tempDir) throws Exception {
        String worldId = "sha-test-world-" + UUID.randomUUID();
        Path testFile = tempDir.resolve("r.0.0.mca");

        // Generate 128 KB of binary payload
        byte[] payload = new byte[131072];
        new Random(42).nextBytes(payload);
        Files.write(testFile, payload);
        String realSha = DeltaManifest.computeSha256(testFile);

        // 1. Upload with matching SHA-256 -> Success (HTTP 200)
        assertDoesNotThrow(() -> client.uploadFile(worldId, "region/r.0.0.mca", testFile, realSha));

        // 2. Download and verify integrity
        Path downloadedFile = tempDir.resolve("downloaded_r.0.0.mca");
        client.downloadFile(worldId, "region/r.0.0.mca", downloadedFile, realSha);
        assertArrayEquals(payload, Files.readAllBytes(downloadedFile), "Downloaded file must match uploaded bytes exactly");

        // 3. Upload with corrupted SHA-256 header -> Must be rejected with HTTP 400
        String corruptedSha = "0000000000000000000000000000000000000000000000000000000000000000";
        IOException corruptEx = assertThrows(IOException.class, () ->
                client.uploadFile(worldId, "region/r.0.1.mca", testFile, corruptedSha));
        assertTrue(corruptEx.getMessage().contains("400") || corruptEx.getMessage().toLowerCase().contains("mismatch"),
                "Expected HTTP 400 or mismatch rejection, got: " + corruptEx.getMessage());
    }

    @Test
    @DisplayName("5. Path traversal protection: attempts to write or read outside world files are strictly rejected")
    void testPathTraversalProtection() throws Exception {
        String worldId = "traversal-world-" + UUID.randomUUID();
        HttpClient rawHttp = HttpClient.newHttpClient();

        // 1. Attempt PUT with encoded path traversal
        HttpRequest putReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/worlds/" + worldId + "/files/..%2f..%2fetc%2fpasswd"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .header("Content-Type", "application/octet-stream")
                .PUT(HttpRequest.BodyPublishers.ofString("root:x:0:0::/root:/bin/sh", StandardCharsets.UTF_8))
                .build();
        HttpResponse<String> putResp = rawHttp.send(putReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(putResp.statusCode() >= 400, "Path traversal PUT must be rejected (>= 400), got: " + putResp.statusCode());

        // 2. Attempt GET with encoded path traversal
        HttpRequest getReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + "/api/v1/worlds/" + worldId + "/files/..%2f..%2fetc%2fpasswd"))
                .header("Authorization", "Bearer " + AUTH_TOKEN)
                .GET()
                .build();
        HttpResponse<String> getResp = rawHttp.send(getReq, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertTrue(getResp.statusCode() >= 400, "Path traversal GET must be rejected (>= 400), got: " + getResp.statusCode());

        // 3. Confirm nothing was written to serverDataDir root or parent
        assertFalse(Files.exists(serverDataDir.resolve("etc/passwd")), "Security violation: etc/passwd written inside storage root");
        assertFalse(Files.exists(serverDataDir.resolve("../etc/passwd")), "Security violation: etc/passwd written above storage root");
    }

    @Test
    @DisplayName("6. Full Delta Push & Pull of multi-megabyte simulated region files, Cobblemon player data, and NBT level.dat player swapping")
    void testFullDeltaPushAndPullMultiMegabyteAndPlayerSwapping(@TempDir Path tempDir) throws Exception {
        String worldId = "cobble-world-" + UUID.randomUUID();
        UUID playerA = UUID.fromString("11111111-1111-1111-1111-111111111111");
        UUID playerB = UUID.fromString("22222222-2222-2222-2222-222222222222");

        // Setup Player A local world
        Path dirA = tempDir.resolve("player_a_saves/SharedPokeWorld");
        Files.createDirectories(dirA.resolve("region"));
        Files.createDirectories(dirA.resolve("cobblemonplayerdata"));

        // Create a 2 MB simulated region file for r.0.0.mca
        byte[] regionBytesA = new byte[2 * 1024 * 1024];
        new Random(101).nextBytes(regionBytesA);
        Files.write(dirA.resolve("region/r.0.0.mca"), regionBytesA);

        // Cobblemon player data for Player A
        Files.writeString(dirA.resolve("cobblemonplayerdata/" + playerA + ".json"), "{\"starter\":\"Charizard\",\"level\":75}");

        // Singleplayer level.dat with Player A inventory
        CompoundTag rootA = new CompoundTag();
        CompoundTag dataA = new CompoundTag();
        CompoundTag tagA = new CompoundTag();
        tagA.putUUID("UUID", playerA);
        tagA.putInt("XpLevel", 75);
        tagA.putString("SpecialItem", "PlayerA_UltraBall");
        dataA.put("Player", tagA);
        dataA.putString("LevelName", "Cobbleverse");
        rootA.put("Data", dataA);
        NbtIo.writeCompressed(rootA, dirA.resolve("level.dat"));

        WorldSyncMetadata metaA = new WorldSyncMetadata(worldId, "SharedPokeWorld", baseUrl, AUTH_TOKEN);
        metaA.save(dirA);

        // --- PHASE 1: Player A launches Singleplayer, exits, and pushes to real Node.js coordinator ---
        WorldSyncOrchestrator.SyncSessionResult launchA = WorldSyncOrchestrator.prepareWorldBeforeLaunch(dirA, playerA, "PlayerA", null);
        assertTrue(launchA.canPlaySingleplayer());
        WorldSyncOrchestrator.finalizeWorldAfterExit(dirA, playerA, null);

        DeltaManifest remoteManifest1 = client.getRemoteManifest(worldId);
        assertTrue(remoteManifest1.revision >= 1L);
        assertTrue(remoteManifest1.files.containsKey("region/r.0.0.mca"));
        assertEquals(regionBytesA.length, remoteManifest1.files.get("region/r.0.0.mca").size());

        // --- PHASE 2: Player B clones world via Invite Token and launches in Singleplayer ---
        Path dirB = WorldSyncOrchestrator.cloneWorldFromInviteToken(
                tempDir.resolve("player_b_saves"),
                "PlayerBSharedWorld",
                metaA.toInviteToken(),
                playerB,
                null
        );

        WorldSyncOrchestrator.SyncSessionResult launchB = WorldSyncOrchestrator.prepareWorldBeforeLaunch(dirB, playerB, "PlayerB", null);
        assertTrue(launchB.canPlaySingleplayer());

        // Verify multi-megabyte region file integrity
        assertArrayEquals(regionBytesA, Files.readAllBytes(dirB.resolve("region/r.0.0.mca")));

        // Verify LevelDatPlayerSwapper prevented inventory cloning!
        CompoundTag levelDatB = NbtIo.readCompressed(dirB.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        assertFalse(levelDatB.getCompound("Data").contains("Player"),
                "Player B must NOT clone Player A's inventory upon first launch");

        // --- PHASE 3: Player B catches a Pokémon, modifies r.0.0.mca, gains XP, and closes game ---
        byte[] regionBytesB = new byte[2 * 1024 * 1024];
        new Random(202).nextBytes(regionBytesB);
        Files.write(dirB.resolve("region/r.0.0.mca"), regionBytesB);
        Files.writeString(dirB.resolve("cobblemonplayerdata/" + playerB + ".json"), "{\"starter\":\"Blastoise\",\"level\":50}");

        CompoundTag playerBTag = new CompoundTag();
        playerBTag.putUUID("UUID", playerB);
        playerBTag.putInt("XpLevel", 50);
        playerBTag.putString("SpecialItem", "PlayerB_MasterBall");
        levelDatB.getCompound("Data").put("Player", playerBTag);
        NbtIo.writeCompressed(levelDatB, dirB.resolve("level.dat"));

        WorldSyncOrchestrator.finalizeWorldAfterExit(dirB, playerB, null);

        // --- PHASE 4: Player A re-opens world -> downloads Player B's delta update, restores Player A inventory ---
        WorldSyncOrchestrator.SyncSessionResult reLaunchA = WorldSyncOrchestrator.prepareWorldBeforeLaunch(dirA, playerA, "PlayerA", null);
        assertTrue(reLaunchA.canPlaySingleplayer());

        // Confirm region file was updated to Player B's version
        assertArrayEquals(regionBytesB, Files.readAllBytes(dirA.resolve("region/r.0.0.mca")));
        // Confirm Player B's Cobblemon data exists
        assertTrue(Files.exists(dirA.resolve("cobblemonplayerdata/" + playerB + ".json")));

        // Confirm Player A's own inventory is cleanly restored into level.dat
        CompoundTag reloadedA = NbtIo.readCompressed(dirA.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag activePlayerTag = reloadedA.getCompound("Data").getCompound("Player");
        assertEquals(75, activePlayerTag.getInt("XpLevel"));
        assertEquals("PlayerA_UltraBall", activePlayerTag.getString("SpecialItem"));
        assertEquals(playerA, activePlayerTag.getUUID("UUID"));

        WorldSyncOrchestrator.finalizeWorldAfterExit(dirA, playerA, null);
    }

    @Test
    @DisplayName("7. Join Request & MCloud LAN Tunnel URL propagation between players")
    void testJoinRequestAndMCloudLanTunnelUrlPropagation() throws Exception {
        String worldId = "lan-world-" + UUID.randomUUID();
        UUID playerA = UUID.randomUUID();
        UUID playerB = UUID.randomUUID();

        // 1. Player A acquires mutex lock
        client.acquireLock(worldId, playerA.toString(), "PlayerA", true);

        // 2. Player B sends a Join Request to Player A
        SyncCoordinatorClient.WorldStatusResponse joinResp = client.requestJoinLan(worldId, playerB.toString(), "PlayerBFriend");
        assertEquals(1, joinResp.joinRequests.size());
        assertEquals("PlayerBFriend", joinResp.joinRequests.get(0).requesterName);

        // 3. Player A's heartbeat detects the request and publishes an active MCloud tunnel URL
        String tunnelUrl = "mcloud-shared-session-987.trycloudflare.com";
        SyncCoordinatorClient.WorldStatusResponse hbResp = client.sendHeartbeat(worldId, playerA.toString(), tunnelUrl);
        assertEquals(tunnelUrl, hbResp.lanTunnelUrl);

        // 4. Player B queries world status and receives the published tunnel URL for direct connection
        SyncCoordinatorClient.WorldStatusResponse statusForB = client.getWorldStatus(worldId);
        assertEquals(tunnelUrl, statusForB.lanTunnelUrl);
        assertEquals(SyncCoordinatorClient.LockState.LOCKED, statusForB.state);
        assertEquals("PlayerA", statusForB.holderName);

        client.releaseLock(worldId, playerA.toString());
    }

    @Test
    @DisplayName("8. Divergent Offline Conflict Resolution: both Uriel and Friend play while coordinator is unreachable, and smart merge preserves both players' inventories/Pokemon and merges regions without data loss")
    void testDivergentOfflineConflictResolution(@TempDir Path tempDir) throws Exception {
        String worldId = "conflict-world-" + UUID.randomUUID();
        UUID uuidUriel = UUID.fromString("11111111-2222-3333-4444-555555555555");
        UUID uuidFriend = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

        // -------------------------------------------------------------
        // Step 1: Base World (Revision 1) created by Uriel
        // -------------------------------------------------------------
        Path urielDir = tempDir.resolve("uriel_saves/PokemonConflictWorld");
        Files.createDirectories(urielDir.resolve("region"));
        Files.createDirectories(urielDir.resolve("cobblemonplayerdata"));

        // Initial Overworld region (v1)
        byte[] regionBaseBytes = new byte[65536];
        new Random(1001).nextBytes(regionBaseBytes);
        Files.write(urielDir.resolve("region/r.0.0.mca"), regionBaseBytes);

        // Uriel starts with a level 25 Charmeleon
        Files.writeString(urielDir.resolve("cobblemonplayerdata/" + uuidUriel + ".json"),
                "{\"party\":[{\"species\":\"Charmeleon\",\"level\":25}]}");

        // Uriel's initial level.dat
        CompoundTag rootUriel = new CompoundTag();
        CompoundTag dataUriel = new CompoundTag();
        CompoundTag playerTagUriel = new CompoundTag();
        playerTagUriel.putUUID("UUID", uuidUriel);
        playerTagUriel.putInt("XpLevel", 25);
        playerTagUriel.putString("SpecialItem", "Uriel_PokeBall");
        dataUriel.put("Player", playerTagUriel);
        dataUriel.putString("LevelName", "ConflictWorld");
        rootUriel.put("Data", dataUriel);
        NbtIo.writeCompressed(rootUriel, urielDir.resolve("level.dat"));

        WorldSyncMetadata urielMeta = new WorldSyncMetadata(worldId, "PokemonConflictWorld", baseUrl, AUTH_TOKEN);
        urielMeta.save(urielDir);

        // Uriel launches and exits -> pushes revision 1 to coordinator
        WorldSyncOrchestrator.SyncSessionResult initialLaunch = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                urielDir, uuidUriel, "Uriel", null
        );
        assertTrue(initialLaunch.canPlaySingleplayer());
        WorldSyncOrchestrator.finalizeWorldAfterExit(urielDir, uuidUriel, null);

        DeltaManifest rev1Manifest = client.getRemoteManifest(worldId);
        assertEquals(1L, rev1Manifest.revision);

        // -------------------------------------------------------------
        // Step 2: Friend clones the world at Revision 1
        // -------------------------------------------------------------
        Path friendDir = WorldSyncOrchestrator.cloneWorldFromInviteToken(
                tempDir.resolve("friend_saves"),
                "FriendConflictWorld",
                urielMeta.toInviteToken(),
                uuidFriend,
                null
        );
        assertEquals(1L, WorldSyncMetadata.load(friendDir).lastSyncedRevision);

        // -------------------------------------------------------------
        // Step 3: Offline divergence begins!
        // Both players play offline while coordinator was simulated as unreachable
        // -------------------------------------------------------------

        // 3A. Friend plays offline from baseRevision 1:
        LocalCrashJournal.markSessionActive(friendDir, uuidFriend.toString(), "Friend", 1L, true);
        LevelDatPlayerSwapper.prepareForLocalPlayer(friendDir, uuidFriend);

        // Friend catches Blastoise lvl 50
        Files.writeString(friendDir.resolve("cobblemonplayerdata/" + uuidFriend + ".json"),
                "{\"party\":[{\"species\":\"Blastoise\",\"level\":50}]}");

        // Friend explores Nether: creates DIM-1/r.0.0.mca
        Files.createDirectories(friendDir.resolve("DIM-1"));
        byte[] netherBytes = new byte[32768];
        new Random(777).nextBytes(netherBytes);
        Files.write(friendDir.resolve("DIM-1/r.0.0.mca"), netherBytes);

        // Friend gains XP lvl 40 and MasterBall in level.dat
        CompoundTag friendLevelDat = NbtIo.readCompressed(friendDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag friendTag = new CompoundTag();
        friendTag.putUUID("UUID", uuidFriend);
        friendTag.putInt("XpLevel", 40);
        friendTag.putString("SpecialItem", "Friend_MasterBall");
        friendLevelDat.getCompound("Data").put("Player", friendTag);
        NbtIo.writeCompressed(friendLevelDat, friendDir.resolve("level.dat"));

        // Friend comes online first! Friend pushes Revision 2 to coordinator
        WorldSyncOrchestrator.finalizeWorldAfterExit(friendDir, uuidFriend, null);
        DeltaManifest rev2Manifest = client.getRemoteManifest(worldId);
        assertEquals(2L, rev2Manifest.revision);
        assertTrue(rev2Manifest.files.containsKey("DIM-1/r.0.0.mca"));
        assertTrue(rev2Manifest.files.containsKey("cobblemonplayerdata/" + uuidFriend + ".json"));

        // 3B. Uriel also played offline from baseRevision 1!
        LocalCrashJournal.markSessionActive(urielDir, uuidUriel.toString(), "Uriel", 1L, true);
        LevelDatPlayerSwapper.prepareForLocalPlayer(urielDir, uuidUriel);

        // Uriel catches Rayquaza lvl 80 and evolves Charizard lvl 70
        Files.writeString(urielDir.resolve("cobblemonplayerdata/" + uuidUriel + ".json"),
                "{\"party\":[{\"species\":\"Charizard\",\"level\":70},{\"species\":\"Rayquaza\",\"level\":80}]}");

        // Uriel modifies the Overworld region (builds a castle)
        byte[] urielCastleBytes = new byte[65536];
        new Random(2002).nextBytes(urielCastleBytes);
        Files.write(urielDir.resolve("region/r.0.0.mca"), urielCastleBytes);

        // Uriel gains XP lvl 99 and GodSword
        CompoundTag urielOfflineLevelDat = NbtIo.readCompressed(urielDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag urielOfflineTag = new CompoundTag();
        urielOfflineTag.putUUID("UUID", uuidUriel);
        urielOfflineTag.putInt("XpLevel", 99);
        urielOfflineTag.putString("SpecialItem", "Uriel_GodSword");
        urielOfflineLevelDat.getCompound("Data").put("Player", urielOfflineTag);
        NbtIo.writeCompressed(urielOfflineLevelDat, urielDir.resolve("level.dat"));

        // -------------------------------------------------------------
        // Step 4: Uriel reconnects to coordinator!
        // Remote revision is 2, Uriel's journal baseRevision is 1 (2 > 1).
        // Divergent conflict detected -> smart player-aware merge executes!
        // -------------------------------------------------------------
        assertTrue(LocalCrashJournal.hasDirtyCrashJournal(urielDir));
        LocalCrashJournal journalBeforeLaunch = LocalCrashJournal.load(urielDir);
        assertEquals(1L, journalBeforeLaunch.baseRevision);
        assertTrue(journalBeforeLaunch.playedOffline);

        WorldSyncOrchestrator.SyncSessionResult mergeLaunch = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                urielDir, uuidUriel, "Uriel", null
        );
        assertTrue(mergeLaunch.canPlaySingleplayer());

        // -------------------------------------------------------------
        // Step 5: Verify Conflict Resolution & Zero Data Loss
        // -------------------------------------------------------------

        // 1. Safety backup exists in .mcloud-conflict-backup
        Path backupDir = urielDir.resolve(".mcloud-conflict-backup");
        assertTrue(Files.isDirectory(backupDir));
        try (var stream = Files.list(backupDir)) {
            assertTrue(stream.findAny().isPresent(), "Safety backup directory must contain timestamped backup");
        }

        // 2. Friend's Cobblemon data is preserved on Uriel's machine
        Path friendCobbleOnUriel = urielDir.resolve("cobblemonplayerdata/" + uuidFriend + ".json");
        assertTrue(Files.exists(friendCobbleOnUriel), "Friend's Cobblemon data must be preserved");
        assertTrue(Files.readString(friendCobbleOnUriel).contains("Blastoise"));

        // 3. Uriel's Cobblemon data was NOT overwritten
        Path urielCobble = urielDir.resolve("cobblemonplayerdata/" + uuidUriel + ".json");
        assertTrue(Files.readString(urielCobble).contains("Rayquaza"));

        // 4. Friend's Nether exploration (DIM-1/r.0.0.mca) was pulled
        Path netherOnUriel = urielDir.resolve("DIM-1/r.0.0.mca");
        assertTrue(Files.exists(netherOnUriel), "Nether region created by friend must be merged");
        assertArrayEquals(netherBytes, Files.readAllBytes(netherOnUriel));

        // 5. Uriel's Overworld castle (region/r.0.0.mca) was kept
        assertArrayEquals(urielCastleBytes, Files.readAllBytes(urielDir.resolve("region/r.0.0.mca")));

        // 6. Uriel's level.dat contains Uriel's GodSword and XpLevel 99
        CompoundTag urielMergedDat = NbtIo.readCompressed(urielDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag urielActiveTag = urielMergedDat.getCompound("Data").getCompound("Player");
        assertEquals(99, urielActiveTag.getInt("XpLevel"));
        assertEquals("Uriel_GodSword", urielActiveTag.getString("SpecialItem"));

        // 7. Verify coordinator received the merged state as Revision 3 immediately upon launch
        DeltaManifest rev3MergedManifest = client.getRemoteManifest(worldId);
        assertEquals(3L, rev3MergedManifest.revision);
        assertTrue(rev3MergedManifest.files.containsKey("cobblemonplayerdata/" + uuidFriend + ".json"));
        assertTrue(rev3MergedManifest.files.containsKey("cobblemonplayerdata/" + uuidUriel + ".json"));
        assertTrue(rev3MergedManifest.files.containsKey("DIM-1/r.0.0.mca"));
        assertTrue(rev3MergedManifest.files.containsKey("region/r.0.0.mca"));

        // Clean exit from Uriel (commits post-session state)
        WorldSyncOrchestrator.finalizeWorldAfterExit(urielDir, uuidUriel, null);
        assertTrue(client.getRemoteManifest(worldId).revision >= 3L);

        // 8. Friend now pulls Revision 3: Friend gets Uriel's castle while keeping Friend's own inventory
        WorldSyncOrchestrator.SyncSessionResult friendLaunchAfterMerge = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                friendDir, uuidFriend, "Friend", null
        );
        assertTrue(friendLaunchAfterMerge.canPlaySingleplayer());

        assertArrayEquals(urielCastleBytes, Files.readAllBytes(friendDir.resolve("region/r.0.0.mca")));
        assertTrue(Files.exists(friendDir.resolve("cobblemonplayerdata/" + uuidUriel + ".json")));

        CompoundTag friendReloaded = NbtIo.readCompressed(friendDir.resolve("level.dat"), NbtAccounter.unlimitedHeap());
        CompoundTag friendActive = friendReloaded.getCompound("Data").getCompound("Player");
        assertEquals(40, friendActive.getInt("XpLevel"));
        assertEquals("Friend_MasterBall", friendActive.getString("SpecialItem"));

        WorldSyncOrchestrator.finalizeWorldAfterExit(friendDir, uuidFriend, null);
    }

    @Test
    @DisplayName("9. Stale lock client-disconnect warning: prepareWorldBeforeLaunch returns isStaleLock=true until explicit force takeover")
    void testPrepareWorldBeforeLaunchStaleLockWarningAndTakeover(@TempDir Path tempDir) throws Exception {
        String worldId = "stale-warning-world-" + UUID.randomUUID();
        UUID friendUuid = UUID.randomUUID();
        UUID localUuid = UUID.randomUUID();

        // 1. Friend acquires lock on world
        SyncCoordinatorClient.LockAcquireResult friendLock = client.acquireLock(worldId, friendUuid.toString(), "CrashyFriend", true);
        assertTrue(friendLock.acquired());

        // 2. Wait 1300ms (> LOCK_TTL_MS=1000) without heartbeat so lock transitions to STALE
        Thread.sleep(1300);

        // Prepare local world directory
        Path localDir = tempDir.resolve("local_world");
        Files.createDirectories(localDir);
        WorldSyncMetadata meta = new WorldSyncMetadata(worldId, "StaleWarningWorld", baseUrl, AUTH_TOKEN);
        meta.save(localDir);

        // 3. Prepare world without force takeover -> Must return isStaleLock = true with warning message!
        WorldSyncOrchestrator.SyncSessionResult warningResult = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                localDir, localUuid, "LocalPlayer", false, null
        );
        assertFalse(warningResult.canPlaySingleplayer(), "Should not launch singleplayer when friend's lock is stale without explicit takeover");
        assertFalse(warningResult.shouldConnectLan());
        assertTrue(warningResult.isStaleLock(), "isStaleLock must be true when lock is STALE");
        assertEquals("CrashyFriend", warningResult.lockedByPlayerName());
        assertTrue(warningResult.message().contains("lost connection"), "Message should inform of lost connection/crash");

        // 4. Default 4-arg prepareWorldBeforeLaunch also defaults to forceTakeoverStale = false
        WorldSyncOrchestrator.SyncSessionResult defaultWarning = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                localDir, localUuid, "LocalPlayer", null
        );
        assertTrue(defaultWarning.isStaleLock(), "4-arg overload must also default to isStaleLock detection");

        // 5. User confirms [Take Over World Now] -> prepareWorldBeforeLaunch with forceTakeoverStale = true succeeds!
        WorldSyncOrchestrator.SyncSessionResult takeoverResult = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                localDir, localUuid, "LocalPlayer", true, null
        );
        assertTrue(takeoverResult.canPlaySingleplayer(), "Should successfully launch singleplayer when forceTakeoverStale is true");
        assertFalse(takeoverResult.isStaleLock());

        // Clean exit releases lock
        WorldSyncOrchestrator.finalizeWorldAfterExit(localDir, localUuid, null);
    }

    @Test
    @DisplayName("10. Patient Waiting Handoff: when previous host exits and is uploading deltas, second player patiently waits, acquires lock upon upload completion, and receives latest files")
    void testPatientWaitingHandoffDuringSlowHostExitUpload(@TempDir Path tempDir) throws Exception {
        String worldId = "patient-wait-world-" + UUID.randomUUID();
        UUID hostUuid = UUID.fromString("11111111-aaaa-bbbb-cccc-111111111111");
        UUID guestUuid = UUID.fromString("22222222-aaaa-bbbb-cccc-222222222222");

        // 1. Setup Host world directory
        Path hostDir = tempDir.resolve("host_saves/PatientWorld");
        Files.createDirectories(hostDir.resolve("region"));
        Files.writeString(hostDir.resolve("region/r.0.0.mca"), "base-region-content");

        // Host initial level.dat
        CompoundTag rootHost = new CompoundTag();
        CompoundTag dataHost = new CompoundTag();
        CompoundTag playerHost = new CompoundTag();
        playerHost.putUUID("UUID", hostUuid);
        playerHost.putInt("XpLevel", 10);
        dataHost.put("Player", playerHost);
        dataHost.putString("LevelName", "PatientWorld");
        rootHost.put("Data", dataHost);
        NbtIo.writeCompressed(rootHost, hostDir.resolve("level.dat"));

        WorldSyncMetadata hostMeta = new WorldSyncMetadata(worldId, "PatientWorld", baseUrl, AUTH_TOKEN);
        hostMeta.save(hostDir);

        // Host launches and exits (commits rev 1)
        WorldSyncOrchestrator.SyncSessionResult initLaunch = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                hostDir, hostUuid, "HostPlayer", false, null
        );
        assertTrue(initLaunch.canPlaySingleplayer());
        WorldSyncOrchestrator.finalizeWorldAfterExit(hostDir, hostUuid, null);

        // 2. Guest clones world at rev 1
        Path guestDir = WorldSyncOrchestrator.cloneWorldFromInviteToken(
                tempDir.resolve("guest_saves"),
                "GuestPatientWorld",
                hostMeta.toInviteToken(),
                guestUuid,
                null
        );
        assertEquals(1L, WorldSyncMetadata.load(guestDir).lastSyncedRevision);

        // 3. Host re-acquires lock to play and opens LAN
        SyncCoordinatorClient.LockAcquireResult hostLock = client.acquireLock(worldId, hostUuid.toString(), "HostPlayer", true);
        assertTrue(hostLock.acquired());
        client.sendHeartbeat(worldId, hostUuid.toString(), "cool-tunnel.trycloudflare.com");

        // 4. Host now starts exiting: clears lanTunnelUrl via heartbeat with ""
        client.sendHeartbeat(worldId, hostUuid.toString(), "");

        // 5. Spawn background thread for Host simulating a slow 3-4 second upload of a new region file
        byte[] hostNewRegionData = new byte[65536];
        new Random(444).nextBytes(hostNewRegionData);
        Files.write(hostDir.resolve("region/r.1.1.mca"), hostNewRegionData);

        Thread hostUploadThread = new Thread(() -> {
            try {
                // Host keeps the lock alive during upload by heartbeating (LOCK_TTL_MS is 1s in test environment)
                for (int h = 0; h < 7; h++) {
                    Thread.sleep(400);
                    client.sendHeartbeat(worldId, hostUuid.toString(), "");
                }

                // Host performs upload and commits rev 2
                WorldSyncOrchestrator.pushWorldDelta(hostDir, hostMeta, client, hostUuid, null);

                // Host finishes upload and releases lock
                client.releaseLock(worldId, hostUuid.toString());
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
        hostUploadThread.start();

        // 6. Guest immediately attempts prepareWorldBeforeLaunch with forceTakeoverStale = true (handoff from DisconnectedScreen)
        java.util.List<String> progressLog = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
        WorldSyncOrchestrator.SyncSessionResult guestResult = WorldSyncOrchestrator.prepareWorldBeforeLaunch(
                guestDir,
                guestUuid,
                "GuestPlayer",
                true,
                (phase, done, total) -> progressLog.add(phase)
        );

        hostUploadThread.join(10000);

        // 7. Verify Guest acquired the lock after waiting and can play Singleplayer
        assertTrue(guestResult.canPlaySingleplayer(), "Guest should acquire lock after host finishes uploading");
        assertFalse(guestResult.isStaleLock());

        // 8. Verify Guest's local folder received the newly uploaded region file
        Path guestNewRegion = guestDir.resolve("region/r.1.1.mca");
        assertTrue(Files.exists(guestNewRegion), "Guest must pull the new region file uploaded by host");
        assertArrayEquals(hostNewRegionData, Files.readAllBytes(guestNewRegion));

        // 9. Verify progress messages were logged
        boolean sawWaitingMessage = progressLog.stream().anyMatch(msg -> msg.contains("Waiting for HostPlayer to finish saving & uploading"));
        assertTrue(sawWaitingMessage, "Progress listener must report waiting status while host is uploading: " + progressLog);

        // Clean exit releases lock
        WorldSyncOrchestrator.finalizeWorldAfterExit(guestDir, guestUuid, null);
    }
}
