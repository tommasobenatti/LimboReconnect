# MCExp build and release guide

## Supported target

- Java 25 or later; build with JDK 25.
- Velocity-CTD 4.3.1-SNAPSHOT build 410. The CTD tree at
  `cf46d4000d252124c280f6361166df57f3d0f7f3` matched build 410 when verified on 24 October 2026.
- Use the paired MCExp LimboAPI 1.1.27-MCExp-CTD410.1 and LimboReconnect 1.1.7-MCExp-CTD410.1.
- Actual integration inputs: ForcePack 1.3.75-SNAPSHOT and nLogin 2.0.18.

Changes address the reported removed-method login error and concrete event/session
lifecycle defects. They do not claim compatibility with every Velocity fork or future CTD build.

## Install, reload and rollback

1. Stop the proxy and keep copies of the previous plugin jars.
2. Replace both plugin jars; leave a single version of each in `plugins/`.
3. Keep both configuration folders. Do not regenerate or delete configuration files.
4. Start the proxy and check login, authentication, pack acceptance/refusal and backend restart in staging.

Existing `config.yml` receives missing schema keys automatically. Messages are embedded
in that file; neither plugin introduces a standalone `messages.yml`. Customized values,
translations, comments, unknown keys and edited/empty lists/maps remain preserved.
No bundled message defaults changed in this patch, so no old translation is replaced.
Invalid files and edits detected during an update are refused. Private backups are
stored under each plugin's `backups/` folder before atomic replacement.

Keep `save-uncompressed-packets: true` with `compatibility-mode: true` in LimboAPI.
Changing compatibility mode requires a full restart. New default pre-login lists
include `nlogin`; existing custom lists are preserved, and replay still uses the original
inbound connection for deferred profile handlers.

Reload LimboAPI via the proxy reload lifecycle (`/velocity reload`); its public
`reload()` method is also available to integrations. There is no `/limboapi reload`
command. LimboReconnect uses `/limboreconnect reload`. These reloads update settings;
the first installation of new binaries requires a stopped proxy.

For rollback, stop the proxy and restore the previous paired jars. If a config rollback
is needed, compare the private backup with later administrator changes before restoring it.

## Build

Download the official [CTD 410 fatjar](https://github.com/GemstoneGG/Velocity-CTD/releases/tag/build-410)
and verify SHA-256:

`7ec86cab7c4187e84ff0312ef2459bd70f96c5484552142b79b82d7c9d25a05d`

From this repository with JDK 25 selected through `JAVA_HOME`:

```sh
./gradlew build \
  -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar \
  -PlimboApiJar=/absolute/path/to/LimboAPI/api/build/libs/api-1.1.27-MCExp-CTD410.1.jar
```

Build the paired LimboAPI repository's `:api:jar` first, using the same verified CTD jar.
Result: `build/libs/LimboReconnect-1.1.7-MCExp-CTD410.1.jar`.
Supply both properties explicitly: the old upstream API lacks the new connection-prepared
query. The original API's transitive compile dependencies remain available when selecting
the local API jar. No Velocity or test classes are bundled. SnakeYAML 2.5 is shaded privately.

## Native CTD queue

`integrate-ctd-queue: true` is added automatically at startup/reload. A reconnecting player
enters the native target queue immediately, including while its backend is offline/full.
CTD owns readiness pings, warmup, priority, FIFO order, dynamic priority, full bypass and
retry limits. An existing target entry is retained. An existing different queue is never
swapped automatically when multiple queues are disabled. This integration applies to
LimboReconnect's configured restart triggers independently of CTD's queue-on-shutdown option.

PAUSED queues wait until joining is permitted. Native queue disabled, a target in
`no-queue-servers`, an INACTIVE target queue, queue-bypass permission or this integration
disabled selects the previous standalone ping path for a new reconnect session.
Runtime queue policy changes never cause an already managed session to force a connection.
Disabling the native queue or integration during an active reconnect switches it to the
standalone ping path. An in-progress handoff first finishes or restores limbo before using
that path. Existing replacement-manager entries are adopted; missing/removed entries are
not inserted again. Each queue operation uses a current local manager/queue snapshot and
retains no references to replaced managers or queues. If the target backend is unregistered,
the controller closes and disconnects with CTD's translated server-does-not-exist message.

The configured CTD server/queue aliases and leave aliases are forwarded through the
native command manager while in reconnect limbo. Normal permissions and command events
still apply; authentication and unrelated proxy commands are not exposed. Queue commands
are limited to one per 250 ms per player. Existing pre-login LimboAPI/nLogin/ForcePack
handling is unaffected by this post-login command bridge.

During an attempted backend connection LimboAPI releases the virtual world. A lifecycle
guard waits for native completion and checks the exact prepared native session handler
before restoring reconnect limbo after failure/cancellation. It never starts a second
connection or recreates the queue entry. Physical disconnects and successful backend
connections cancel the guard, leaving native queue reservation cleanup to CTD.

Leaving a queue, native max retries, native banned reasons, rejected protocol eligibility
and an explicit queue swap do not cause automatic re-enqueue. An active player without
a backend remains in limbo and can use the configured queue/server commands or disconnect.
See [native queue tests and limits](ctd-native-queue.tdd.md).

## Validation and limitations

The original CTD410 patch had 34 focused reconnect tests (85 across the pair). The native
queue release adds unit regressions and tests against CTD's actual queue implementation;
see [current native queue evidence](ctd-native-queue.tdd.md). Whole-project coverage was
not measured and the audit is scoped to login, resource packs, reconnect and resource lifecycle.

The isolated smoke used real CTD/ForcePack/nLogin jars, offline protocol 765,
a local pack and a fake backend. One same-socket CONFIG hold measured 36.00048s;
three logins and two reloads of each plugin passed with unchanged config hashes,
active nLogin profile mutation and successful ForcePack statuses. The sandbox's
read-timeout was 75s because the fake backend does not send heartbeat/world data.
The CTD identity-lock lifecycle was unmodified. No production settings, database,
credentials, license or cache were copied. No production server was changed.

Rendering, premium/password authentication and a complete PLAY→backend restart→limbo→return
cycle remain staging checks. Details: [smoke result](smoke-verification.md) and
[regression evidence](velocity-ctd-compatibility.tdd.md).

## Release procedure

CI builds push, pull-request and published-release commits on JDK 25 with the verified
CTD inputs. It has read-only repository permissions and uploads jars/test evidence.
It does not delete or rewrite dev tags, replace release assets or publish to upstream Modrinth projects.

Create an annotated version tag on a verified commit. Rebuild the tagged commit,
check its embedded Git revision, run the paired local smoke, compute SHA256SUMS and
publish the corresponding jar, checksum and documentation with `gh release create --verify-tag`.
GitHub's source archives identify the tagged source; the release asset checksum identifies
the uploaded binary. Release publication triggers another read-only CI check.


### Pinned companion API / CI matrix

The new prepared-handler API is built from LimboAPI commit `259704d37c9bad334de2237010a81b6f08cecac4`.
CI checks out that exact source and builds :api:jar with the matrix's verified CTD
artifact, then supplies it through -PlimboApiJar. It never silently compiles this
release against the older upstream interface. Both CTD410 and411 pass the full
suite; the paired API suite73 plus Reconnect71 total144 tests per target build.
The public guide and release notes explicitly retain the staging limitations for
full live PLAY restart/reconnect, premium/password authentication and distributed Redis.
