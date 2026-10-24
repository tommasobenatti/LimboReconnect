# Changelog

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
