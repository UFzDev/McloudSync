# 🏛️ ESPECIFICACIÓN TÉCNICA — MCLOUD SYNC (v1.0.0)

## 1. Propósito del Sistema
**MCloud Sync (`mcloudsync`)** es una extensión oficial y dependiente al 100% de **MCloud (`mcloud`)** que permite a dos o más jugadores compartir un mismo mundo de Minecraft (Vanilla o con modpacks pesados) **sin necesidad de pagar ni mantener un servidor de Minecraft dedicado 24/7**.

Cada jugador ejecuta el motor pesado de Minecraft en su propia PC cuando desea jugar en solitario, mientras que un micro-coordinador ultraligero (consumo < 25 MB RAM, 0.1% CPU) gestiona el candado de sesión (Mutex) y la sincronización diferencial por archivos (Delta Sync). Cuando dos jugadores coinciden en horario, `MCloud Sync` coordina la apertura automática en LAN a través del motor de túneles WebSocket de `MCloud`.

---

## 2. Reglas de Oro de Ingeniería (Anti-Corrupción)

### 2.1. Opt-In Estricto por Mundo (`.mcloud-sync.json`)
- Ningún mundo local se toca a menos que contenga el archivo `.mcloud-sync.json` en su directorio raíz (`saves/<NombreMundo>/.mcloud-sync.json`).
- El archivo contiene:
  ```json
  {
    "enabled": true,
    "worldId": "uuid-del-mundo",
    "coordinatorUrl": "https://sync-dominio.trycloudflare.com",
    "authToken": "token-secreto-compartido",
    "lastSyncedRevision": 12
  }
  ```
- Incluye soporte para **Token de Invitación (`InviteToken`)** codificado en Base64URL (`mcloudsync://<base64>`) para que el segundo jugador pueda vincular y descargar el mundo por primera vez con un solo comando o botón.

### 2.2. Solución al Bug del Inventario Clonado en Singleplayer (`level.dat` vs `playerdata/<UUID>.dat`)
En Minecraft Singleplayer (`IntegratedServer`), el motor guarda el estado del jugador anfitrión dentro de la etiqueta NBT `Data.Player` en `level.dat`. Si el Jugador B descarga ese `level.dat` y lo abre en Singleplayer, Minecraft carga al Jugador B con el inventario, posición y experiencia del Jugador A.

**Algoritmo determinista de `LevelDatPlayerSwapper`:**
1. **Al Empaquetar / Subir (Post-Session)**:
   - Se lee `level.dat` (GZIP NBT).
   - Si existe `Data.Player`, se respalda en `playerdata/<hostUUID>.dat` (si es más reciente) y **se elimina la etiqueta `Player` de `Data` dentro de `level.dat`** (o se reemplaza al cargar).
2. **Antes de Cargar el Mundo Local (Pre-Session)**:
   - Se identifica el UUID del jugador actual (`Minecraft.getInstance().getUser().getProfileId()`).
   - Se busca `playerdata/<currentPlayerUUID>.dat`.
   - Si existe, se inyecta su compuesto NBT dentro de `Data.Player` en `level.dat`.
   - Si no existe (primera vez que este jugador entra al mundo), se elimina `Data.Player` de `level.dat` para que Minecraft lo spawnee limpio como jugador nuevo en el World Spawn sin robar el inventario del amigo.
3. **Compatibilidad con Mods (Xaero, modpacks técnicos/mágicos, etc.)**:
   - Se incluyen todas las subcarpetas de datos por UUID del mundo: `playerdata/`, `cobblemonplayerdata/`, `advancements/`, `stats/`, `data/`, `region/`, `entities/`, `poi/`, `DIM-1/`, `DIM1/`, y dimensiones de mods (`dimensions/`).

### 2.3. Sincronización Delta por Archivos (Evasión del Límite HTTP 413 de 100 MB en Cloudflare)
- Prohibido comprimir todo el mundo en un `.zip` monolítico.
- `DeltaManifestBuilder` escanea recursivamente la carpeta del mundo excluyendo archivos efímeros (`session.lock`, `.mcloud-sync.json`, `.mcloud-local.lock`, `*.tmp`, `icon.png` opcional).
- Calcula el hash `SHA-256` y tamaño en bytes de cada archivo relativo (ej. `region/r.0.0.mca`).
- **Pull (Descarga antes de jugar)**:
  - Compara el manifiesto local con `GET /api/v1/worlds/:worldId/manifest`.
  - Descarga únicamente los archivos cuyo `sha256` difiera o no existan localmente, y elimina archivos que hayan sido borrados en la revisión remota.
- **Push (Subida al cerrar el mundo)**:
  - Calcula el nuevo manifiesto local tras el guardado de `IntegratedServer`.
  - Sube mediante `PUT /api/v1/worlds/:worldId/files/<ruta>` únicamente los archivos cuyo `sha256` haya cambiado respecto al manifiesto del servidor.
  - Confirma la nueva revisión con `POST /api/v1/worlds/:worldId/commit`.

### 2.4. Mutex de Sesión, Heartbeat y Protección contra "Candado Zombie" (Crash Recovery)
- **Estados del Candado en el Coordinador**:
  - `FREE`: Nadie está jugando.
  - `LOCKED`: Un jugador tiene la sesión abierta (`holderUuid`, `holderName`, `acquiredAt`, `lastHeartbeatAt`, `lanTunnelUrl`, `joinRequestedBy`).
- **TTL Automático**: Si el coordinador no recibe un `POST /api/v1/worlds/:worldId/heartbeat` en **90 segundos** (el cliente envía cada 25 segundos), el candado pasa a estado `STALE_LOCK`.
- **Bitácora de Rescate Local (`.mcloud-local.lock`)**:
  - Al adquirir el candado y abrir el mundo, se escribe `.mcloud-local.lock` con `{ "dirty": true, "sessionStart": ... }`.
  - Al terminar de subir exitosamente los cambios al cerrar el mundo, se borra `.mcloud-local.lock`.
  - Si Minecraft crashea (`Exit Code 1` o apagón), la próxima vez que el jugador inicie Minecraft, `MCloudSyncMod` detecta `.mcloud-local.lock` y ejecuta una **Subida de Recuperación Automática** antes de liberar el candado.

### 2.5. Sinergia en Tiempo Real con MCloud (Join Request -> Auto LAN Tunnel)
- Cuando el Jugador A está jugando en solitario:
  - El hilo de Heartbeat (cada 10-15s) consulta el estado del coordinador.
- Cuando el Jugador B intenta entrar al mundo y ve que está `LOCKED` por el Jugador A:
  - Si `lanTunnelUrl` ya está activo: el botón permite **"Unirse a partida LAN (MCloud)"** directamente sin sincronizar archivos locales.
  - Si `lanTunnelUrl` es nulo: el Jugador B pulsa **"Solicitar unirse (Abrir LAN)"** (`POST /api/v1/worlds/:worldId/join-request`).
- El Jugador A recibe en su siguiente pulso la notificación `joinRequestedBy = "Jugador B"`:
  - Muestra un Toast / Mensaje interactivo en el chat de Minecraft con botón `[ABRIR LAN CON MCLOUD]`.
  - Al aceptarlo (o ejecutar `/mcloudsync share`), publica el `IntegratedServer` en un puerto libre, invoca `CloudflaredManager` de **MCloud**, obtiene la URL `*.trycloudflare.com` y la registra en el coordinador (`POST /api/v1/worlds/:worldId/lan-url`).
  - El cliente del Jugador B detecta la `lanTunnelUrl` y se conecta automáticamente usando la tubería WebSocket de `MCloud`.

---

## 3. Contrato de la API del Coordinador (`mcloudsync-server`)
Servicio HTTP ligero en Node/Bun o Python (incluido con `Dockerfile` y `docker-compose.yml` listo para correr en el servidor Acer Linux o cualquier VPS/PC):

| Método | Ruta | Descripción |
| :--- | :--- | :--- |
| `GET` | `/api/v1/health` | Estado del coordinador. |
| `GET` | `/api/v1/worlds/:id/status` | Devuelve estado del candado (`FREE`, `LOCKED`, `STALE`), dueño actual, `revision`, `lanTunnelUrl` y `joinRequests`. |
| `POST` | `/api/v1/worlds/:id/lock` | Adquiere el candado (`holderUuid`, `holderName`). Falla con `409 Conflict` si otro jugador activo lo tiene. |
| `POST` | `/api/v1/worlds/:id/heartbeat` | Renueva el TTL del candado, actualiza opcionalmente `lanTunnelUrl`, y devuelve `joinRequests` pendientes. |
| `POST` | `/api/v1/worlds/:id/unlock` | Libera el candado tras completar el `commit`. |
| `POST` | `/api/v1/worlds/:id/join-request` | Registra que un amigo quiere unirse por LAN (`requesterUuid`, `requesterName`). |
| `GET` | `/api/v1/worlds/:id/manifest` | Devuelve el `manifest.json` actual (`revision`, mapa de `path -> { sha256, size }`). |
| `GET` | `/api/v1/worlds/:id/files/*` | Descarga el binario crudo de un archivo específico del mundo. |
| `PUT` | `/api/v1/worlds/:id/files/*` | Sube un archivo modificado validando su cabecera `X-File-Sha256`. |
| `POST` | `/api/v1/worlds/:id/commit` | Confirma el nuevo manifiesto completo tras subir los deltas y elimina archivos obsoletos en el servidor. |
