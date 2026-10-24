# Changelog

## 1.1.7-MCExp-CTD410.1 — 2026-10-24

- Integrate reconnect limbo with CTD's native queue by default, using its public queue API once per reconnect session.
- Preserve existing entries, priority/FIFO order, full/offline/warmup checks, paused queues, version eligibility and native retry removal.
- Keep standalone ping reconnects when integration/the native queue is disabled, the target is excluded/inactive or the player bypasses queues.
- Recover limbo after failed/cancelled native attempts without re-enqueueing; retain the guard across LimboAPI's connection handoff.
- Cancel guards on disconnect, backend connection, unrelated session takeover and shutdown without altering CTD's disconnect reservations.
- Forward only configured native queue/leave command aliases, with native permissions and a per-player command throttle.
- Recover transient connection kicks during a managed queue retry independently of the initial restart regex, retaining other plugins' decisions and CTD banned reasons.
- Follow replaced native queue managers without retaining old objects or re-inserting removed entries; use standalone reconnects if the native queue is disabled.
- Gracefully disconnect if the target server is unregistered instead of leaving an orphan controller or pinging an obsolete backend.
- Add `integrate-ctd-queue` automatically with the existing conservative YAML updater.

Requires paired LimboAPI 1.1.27-MCExp-CTD410.1. Native queue tests exercise the actual
CTD 410 manager, queue and entry implementations. See docs/ctd-native-queue.tdd.md.

## 1.1.7-MCExp-CTD410 — 2026-10-24

- Cancel timers, delayed joins and pending backend pings on disconnect; ignore late callbacks.
- Serialize ping completion on the player's executor and retry synchronous ping failures.
- Chain existing kick callbacks and preserve changed decisions from other plugins.
- Handle empty title lists, clamp legacy sound pitch and dispose replaced limbo instances.
- Declare LimboAPI as a required plugin dependency.
- Upgrade config.yml conservatively on startup/reload with strict validation, private backups and atomic replacement.
- Preserve translations, comments, unknown keys, edited/empty collections and concurrent administrator edits.
- Pin CI to the verified CTD artifact and publish CI jars/test reports without automatic external uploads.

Validation: 34 unit/regression tests; full Gradle build, Checkstyle, SpotBugs and licenses pass.
Paired compatibility smoke: CTD 410, ForcePack 1.3.75-SNAPSHOT, nLogin 2.0.18,
three offline protocol-765 logins, a 36-second CONFIG hold and two reloads per plugin.
See docs/MCEXP.md for scope, build instructions and remaining staging checks.
