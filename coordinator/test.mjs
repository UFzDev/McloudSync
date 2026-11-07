import test from 'node:test';
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import path from 'node:path';
import fsp from 'node:fs/promises';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const TEST_PORT = 18787;
const TEST_DATA_DIR = path.resolve(__dirname, './test_data');

test('MCloudSync Coordinator persistence and mutex flow', async (t) => {
  await fsp.rm(TEST_DATA_DIR, { recursive: true, force: true });
  await fsp.mkdir(TEST_DATA_DIR, { recursive: true });

  const child = spawn(process.execPath, [path.resolve(__dirname, 'server.mjs')], {
    env: {
      ...process.env,
      PORT: String(TEST_PORT),
      DATA_DIR: TEST_DATA_DIR,
      LOCK_TTL_MS: '5000'
    },
    stdio: 'pipe'
  });

  t.after(async () => {
    child.kill('SIGTERM');
    await fsp.rm(TEST_DATA_DIR, { recursive: true, force: true });
  });

  // Wait for server to come online
  const baseUrl = `http://127.0.0.1:${TEST_PORT}`;
  let online = false;
  for (let i = 0; i < 30; i++) {
    try {
      const res = await fetch(`${baseUrl}/api/v1/health`);
      if (res.status === 200) {
        online = true;
        break;
      }
    } catch {}
    await new Promise(r => setTimeout(r, 100));
  }
  assert.equal(online, true, 'Server must be online and responding on /health');

  const worldId = 'test-world-persistence';

  // 1. Acquire Lock
  const lockRes = await fetch(`${baseUrl}/api/v1/worlds/${worldId}/lock`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ holderUuid: 'uuid-player-1', holderName: 'Player1' })
  });
  assert.equal(lockRes.status, 200);
  const lockData = await lockRes.json();
  assert.equal(lockData.state, 'LOCKED');
  assert.equal(lockData.holderUuid, 'uuid-player-1');

  // 2. Verify locks.json was written to disk
  const locksFile = path.resolve(TEST_DATA_DIR, 'locks.json');
  const locksRaw = await fsp.readFile(locksFile, 'utf8');
  const diskLocks = JSON.parse(locksRaw);
  assert.ok(diskLocks[worldId], 'World must be recorded in locks.json');
  assert.equal(diskLocks[worldId].state, 'LOCKED');
  assert.equal(diskLocks[worldId].holderUuid, 'uuid-player-1');

  // 3. Second player cannot acquire
  const lockConflictRes = await fetch(`${baseUrl}/api/v1/worlds/${worldId}/lock`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ holderUuid: 'uuid-player-2', holderName: 'Player2' })
  });
  assert.equal(lockConflictRes.status, 409);

  // 4. Release lock
  const unlockRes = await fetch(`${baseUrl}/api/v1/worlds/${worldId}/unlock`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ holderUuid: 'uuid-player-1' })
  });
  assert.equal(unlockRes.status, 200);
  const unlockData = await unlockRes.json();
  assert.equal(unlockData.state, 'FREE');

  // 5. Verify disk state updated to FREE
  const updatedLocksRaw = await fsp.readFile(locksFile, 'utf8');
  const updatedDiskLocks = JSON.parse(updatedLocksRaw);
  assert.equal(updatedDiskLocks[worldId].state, 'FREE');
});
