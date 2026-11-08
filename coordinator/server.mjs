/**
 * MCloudSync Coordinator Server (Zero-Dependency Node.js 20+ / Bun Runtime)
 *
 * Ultra-lightweight (<25 MB RAM, 0.1% CPU) World Mutex & SHA-256 Delta Storage API.
 * Designed to run 24/7 on low-power home servers (e.g., Acer Pentium E5400 Linux, Raspberry Pi)
 * exposed via Cloudflare Tunnel without running the heavy Minecraft JVM.
 */

import http from 'node:http';
import fs from 'node:fs';
import fsp from 'node:fs/promises';
import path from 'node:path';
import crypto from 'node:crypto';

const PORT = parseInt(process.env.PORT || '8787', 10);
const DATA_DIR = path.resolve(process.env.DATA_DIR || './data');
const AUTH_TOKEN = (process.env.AUTH_TOKEN || '').trim();
const LOCK_TTL_MS = parseInt(process.env.LOCK_TTL_MS || '90000', 10); // 90 seconds default TTL
const LOCKS_FILE = path.resolve(DATA_DIR, 'locks.json');

await fsp.mkdir(DATA_DIR, { recursive: true });

// Mutex state persisted alongside world metadata in locks.json
const worldLocks = new Map();

async function loadLocks() {
  try {
    const raw = await fsp.readFile(LOCKS_FILE, 'utf8');
    const data = JSON.parse(raw);
    for (const [worldId, lock] of Object.entries(data)) {
      worldLocks.set(worldId, lock);
    }
    console.log(`[MCloudSync Coordinator] Loaded ${worldLocks.size} lock state(s) from ${LOCKS_FILE}`);
  } catch {
    // First run or file not created yet
  }
}

async function saveLocks() {
  try {
    const obj = Object.fromEntries(worldLocks.entries());
    const tempPath = `${LOCKS_FILE}.tmp.${Date.now()}`;
    await fsp.writeFile(tempPath, JSON.stringify(obj, null, 2), 'utf8');
    await fsp.rename(tempPath, LOCKS_FILE);
  } catch (err) {
    console.error('[MCloudSync Coordinator] Failed to persist locks.json:', err.message);
  }
}

await loadLocks();

function sanitizeWorldId(rawId) {
  return String(rawId || '').replace(/[^a-zA-Z0-9_-]/g, '_');
}

function resolveSafeWorldFile(worldId, relPath) {
  const normalizedRel = String(relPath || '').replace(/\\/g, '/');
  const worldRoot = path.resolve(DATA_DIR, sanitizeWorldId(worldId), 'files');
  const target = path.resolve(worldRoot, normalizedRel);
  if (!target.startsWith(worldRoot + path.sep)) {
    throw new Error('Blocked path traversal');
  }
  return { worldRoot, target };
}

function getManifestPath(worldId) {
  return path.resolve(DATA_DIR, sanitizeWorldId(worldId), 'manifest.json');
}

async function loadManifest(worldId) {
  const p = getManifestPath(worldId);
  try {
    const raw = await fsp.readFile(p, 'utf8');
    return JSON.parse(raw);
  } catch {
    return { revision: 0, files: {} };
  }
}

async function saveManifest(worldId, manifest) {
  const p = getManifestPath(worldId);
  await fsp.mkdir(path.dirname(p), { recursive: true });
  await fsp.writeFile(p, JSON.stringify(manifest, null, 2), 'utf8');
}

function evaluateWorldState(worldId, revision) {
  const now = Date.now();
  let lock = worldLocks.get(worldId);
  if (!lock) {
    lock = {
      worldId,
      state: 'FREE',
      holderUuid: null,
      holderName: null,
      acquiredAt: 0,
      lastHeartbeatAt: 0,
      revision,
      lanTunnelUrl: null,
      joinRequests: []
    };
    worldLocks.set(worldId, lock);
  }
  lock.revision = revision;

  if (lock.state === 'LOCKED' && !lock.lanTunnelUrl && now - lock.lastHeartbeatAt > LOCK_TTL_MS) {
    lock.state = 'STALE';
  }
  return lock;
}

function sendJson(res, statusCode, payload) {
  const body = JSON.stringify(payload);
  res.writeHead(statusCode, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(body)
  });
  res.end(body);
}

async function readJsonBody(req) {
  const chunks = [];
  for await (const chunk of req) {
    chunks.push(chunk);
  }
  const raw = Buffer.concat(chunks).toString('utf8');
  return raw ? JSON.parse(raw) : {};
}

const server = http.createServer(async (req, res) => {
  try {
    const url = new URL(req.url, `http://${req.headers.host || 'localhost'}`);
    const pathname = url.pathname;

    if (req.method === 'GET' && pathname === '/api/v1/health') {
      return sendJson(res, 200, { status: 'ok', service: 'mcloudsync-coordinator', uptime: process.uptime() });
    }

    // Verify Bearer token if configured
    if (AUTH_TOKEN) {
      const authHeader = req.headers['authorization'] || '';
      if (authHeader !== `Bearer ${AUTH_TOKEN}`) {
        return sendJson(res, 401, { error: 'Unauthorized: invalid or missing Bearer token' });
      }
    }

    const match = pathname.match(/^\/api\/v1\/worlds\/([^/]+)\/(.+)$/);
    if (!match) {
      return sendJson(res, 404, { error: 'Route not found' });
    }

    const worldId = decodeURIComponent(match[1]);
    const action = match[2];
    const manifest = await loadManifest(worldId);
    const lock = evaluateWorldState(worldId, manifest.revision);

    // 1. GET /api/v1/worlds/:id/status
    if (req.method === 'GET' && action === 'status') {
      return sendJson(res, 200, lock);
    }

    // 2. POST /api/v1/worlds/:id/lock
    if (req.method === 'POST' && action === 'lock') {
      const body = await readJsonBody(req);
      const { holderUuid, holderName, forceTakeoverIfStale } = body;
      const now = Date.now();

      const canAcquire =
        lock.state === 'FREE' ||
        lock.holderUuid === holderUuid ||
        (lock.state === 'STALE' && forceTakeoverIfStale === true);

      if (!canAcquire) {
        return sendJson(res, 409, lock);
      }

      lock.state = 'LOCKED';
      lock.holderUuid = holderUuid;
      lock.holderName = holderName || 'Player';
      lock.acquiredAt = now;
      lock.lastHeartbeatAt = now;
      lock.lanTunnelUrl = null;
      lock.joinRequests = [];

      await saveLocks();
      return sendJson(res, 200, lock);
    }

    // 3. POST /api/v1/worlds/:id/heartbeat
    if (req.method === 'POST' && action === 'heartbeat') {
      const body = await readJsonBody(req);
      const { holderUuid, lanTunnelUrl } = body;
      if (lock.holderUuid && lock.holderUuid !== holderUuid) {
        return sendJson(res, 409, lock);
      }
      lock.state = 'LOCKED';
      lock.holderUuid = holderUuid || lock.holderUuid;
      lock.lastHeartbeatAt = Date.now();
      if (lanTunnelUrl !== undefined) {
        lock.lanTunnelUrl = lanTunnelUrl;
      }
      await saveLocks();
      return sendJson(res, 200, lock);
    }

    // 4. POST /api/v1/worlds/:id/unlock
    if (req.method === 'POST' && action === 'unlock') {
      const body = await readJsonBody(req);
      if (!lock.holderUuid || lock.holderUuid === body.holderUuid || lock.state === 'STALE') {
        lock.state = 'FREE';
        lock.holderUuid = null;
        lock.holderName = null;
        lock.lanTunnelUrl = null;
        lock.joinRequests = [];
        await saveLocks();
      }
      return sendJson(res, 200, lock);
    }

    // 5. POST /api/v1/worlds/:id/join-request
    if (req.method === 'POST' && action === 'join-request') {
      const body = await readJsonBody(req);
      const { requesterUuid, requesterName } = body;
      if (requesterUuid) {
        const exists = lock.joinRequests.some(r => r.requesterUuid === requesterUuid);
        if (!exists) {
          lock.joinRequests.push({
            requesterUuid,
            requesterName: requesterName || 'Friend',
            requestedAt: Date.now()
          });
          await saveLocks();
        }
      }
      return sendJson(res, 200, lock);
    }

    // 6. GET /api/v1/worlds/:id/manifest
    if (req.method === 'GET' && action === 'manifest') {
      return sendJson(res, 200, manifest);
    }

    // 7. GET or PUT /api/v1/worlds/:id/files/*
    if (action.startsWith('files/')) {
      const relPath = action.substring('files/'.length).split('/').map(decodeURIComponent).join('/');
      const { target } = resolveSafeWorldFile(worldId, relPath);

      if (req.method === 'GET') {
        try {
          const stat = await fsp.stat(target);
          res.writeHead(200, {
            'Content-Type': 'application/octet-stream',
            'Content-Length': stat.size
          });
          fs.createReadStream(target).pipe(res);
          return;
        } catch {
          return sendJson(res, 404, { error: 'File not found: ' + relPath });
        }
      }

      if (req.method === 'PUT') {
        await fsp.mkdir(path.dirname(target), { recursive: true });
        const tempFile = `${target}.tmp.${Date.now()}`;
        const hash = crypto.createHash('sha256');
        let bytesWritten = 0;

        await new Promise((resolve, reject) => {
          const out = fs.createWriteStream(tempFile);
          req.on('data', chunk => {
            hash.update(chunk);
            bytesWritten += chunk.length;
          });
          req.pipe(out);
          out.on('finish', resolve);
          out.on('error', reject);
          req.on('error', reject);
        });

        const computedSha = hash.digest('hex');
        const expectedSha = req.headers['x-file-sha256'];
        if (expectedSha && expectedSha.toLowerCase() !== computedSha.toLowerCase()) {
          await fsp.unlink(tempFile).catch(() => {});
          return sendJson(res, 400, { error: `SHA-256 mismatch: expected ${expectedSha}, got ${computedSha}` });
        }

        await fsp.rename(tempFile, target);
        return sendJson(res, 200, { path: relPath, sha256: computedSha, size: bytesWritten });
      }
    }

    // 8. POST /api/v1/worlds/:id/commit
    if (req.method === 'POST' && action === 'commit') {
      const body = await readJsonBody(req);
      const newManifest = body.manifest;
      if (!newManifest || typeof newManifest.files !== 'object') {
        return sendJson(res, 400, { error: 'Invalid manifest payload' });
      }

      // Delete obsolete files no longer present in the new manifest
      const oldFiles = Object.keys(manifest.files || {});
      const newFilesSet = new Set(Object.keys(newManifest.files));
      for (const oldRelPath of oldFiles) {
        if (!newFilesSet.has(oldRelPath)) {
          try {
            const { target } = resolveSafeWorldFile(worldId, oldRelPath);
            await fsp.unlink(target);
          } catch {}
        }
      }

      const nextRev = Math.max((manifest.revision || 0) + 1, newManifest.revision || 1);
      const committed = {
        revision: nextRev,
        files: newManifest.files
      };
      await saveManifest(worldId, committed);
      lock.revision = nextRev;
      await saveLocks();
      return sendJson(res, 200, committed);
    }

    return sendJson(res, 405, { error: 'Method or action not allowed' });
  } catch (err) {
    if (err.message === 'Blocked path traversal') {
      return sendJson(res, 400, { error: 'Blocked path traversal' });
    }
    console.error('[MCloudSync-Coordinator] Error:', err);
    return sendJson(res, 500, { error: err.message || 'Internal Server Error' });
  }
});

process.on('SIGTERM', async () => {
  await saveLocks();
  process.exit(0);
});

process.on('SIGINT', async () => {
  await saveLocks();
  process.exit(0);
});

server.listen(PORT, '0.0.0.0', () => {
  console.log(`[MCloudSync Coordinator] Listening on http://0.0.0.0:${PORT}`);
  console.log(`[MCloudSync Coordinator] Storage directory: ${DATA_DIR}`);
});
