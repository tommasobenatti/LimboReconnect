# Native CTD queue integration — 24 October 2026

Journeys were derived from the request to integrate reconnect limbo with CTD's queue.
The previous CTD410 release used standalone ping/reconnect and did not enqueue players.
This release uses the actual public `ProxyServer.isQueueEnabled()`, `getQueueManager()`
and `QueueManager.queue(player, target)` interfaces from the pinned CTD 410 binary.

## Journeys and guarantees

| Guarantee | Executed tests | Result |
| --- | --- | --- |
| A reconnect immediately joins the native queue once; standalone backend pings stop | `ReconnectHandlerTest.nativeQueueTakesOwnershipImmediatelyWithoutPinging` | PASS |
| An existing entry retains the same object, priority and FIFO position | `CtdQueueIntegrationTest.nativeManagerPreservesPriorityFifoAndExistingEntryIdentity` | PASS, actual CTD manager/queue/entries |
| Offline, warmup, full and paused transfer rules remain native | `CtdQueueIntegrationTest.offlineWarmupFullAndPausedRemainUnderNativeTransferPolicy` | PASS, actual CTD transfer method |
| Paused/disallowed joins wait; leaving while paused cancels delayed insertion | `ReconnectHandlerTest.pausedQueueWaitsUntilJoiningIsAllowed`, `leavingWhilePausedPreventsDelayedAutomaticEnqueue` | PASS |
| Listing servers and denied server selection do not cancel a paused reconnect | `ReconnectHandlerTest.listingServersWhilePausedDoesNotCancelAutomaticEnqueue`, `deniedServerSelectionWhilePausedDoesNotCancelAutomaticEnqueue` | PASS |
| Disabled integration, inactive/excluded targets and queue bypass preserve standalone ping reconnects | `ReconnectHandlerTest` fallback cases | PASS |
| Existing different queues are not swapped automatically | `ReconnectHandlerTest.existingOtherQueueIsNeverSwappedAutomatically` | PASS |
| Native version rejection does not loop, force a connect or reinsert | `CtdQueueIntegrationTest.nativeVersionRejectionDoesNotLoopOrForceAConnection` | PASS, actual native version gate |
| Queue leave/native removal never recreates an entry | `CtdQueueIntegrationTest.explicitRemovalNeverRecreatesTheNativeEntry`, `ReconnectHandlerTest.removedQueueEntryRestoresLimboWithoutReaddingPlayer` | PASS |
| Limbo release preserves a guard while native attempts run; failure recovers limbo after one event-loop grace turn | `ReconnectHandlerTest.nativeHandoffGuardWaitsForNativeFailureBeforeRestoringLimbo` | PASS |
| A fallback in flight, unrelated handler or physical disconnect prevents resurrection | `ReconnectHandlerTest.inFlightFallbackPreventsLimboRecovery`, `unrelatedSessionHandlerStopsRecoveryGuard`, `physicalDisconnectCancelsAllNativeGuardTasksWithoutRemovingEntry` | PASS |
| A closing executor releases the guard/registry rather than throwing or leaking | `ReconnectHandlerTest.rejectedRecoveryTurnClosesTheGuard` | PASS |
| Only configured native queue/leave aliases execute; auth and unrelated administrative commands remain blocked | `ReconnectHandlerTest.onlyConfiguredNativeQueueCommandsAreForwarded`, `configuredNativeAliasesAreForwarded` | PASS |
| Transient retry kicks outside the initial restart regex require a managed target; native banned reasons and previous authentication decisions remain authoritative | `ReconnectListenerTest`, `ReconnectHandlerTest.nativeBannedReasonRejectsManagedQueueKick` | PASS |
| Manager replacement adopts only existing entries; global disable switches to standalone after safe handoff recovery | `CtdQueueIntegrationTest` replacement cases; `ReconnectHandlerTest` global-disable cases | PASS |
| Reload/disable during queue or kick inspection cannot dereference stale manager/queue objects or drop owned handoff authorization | `CtdQueueIntegrationTest.disableBetweenQueueReadsCannotThrowOrAbandonTheGuard`, `ReconnectHandlerTest.nativeDisableDuringKickInspectionKeepsOwnedAuthorization` | PASS |
| Removed targets terminate cleanly without obsolete pings or orphan controllers | `ReconnectHandlerTest.removedTargetQueueLookupTerminatesControllerWithoutAnOrphan`, `standaloneFallbackNeverPingsAnUnregisteredTarget` | PASS |
| Queue manager shutdown between the enabled check and manager acquisition safely switches to standalone | `ReconnectHandlerTest.unavailableManagerDuringReloadDoesNotAbandonTheGuard` | PASS |
| Startup/reload adds the default key on disk, preserves custom YAML/comments and is idempotent | `LimboReconnectLifecycleTest.reloadAddsMissingDefaultsAndPreservesUnknownKeysAndComments`, updater tests | PASS |

The native integration tests instantiate the actual `VelocityQueueManager`,
`LocalVelocityQueue`, `QueuePlayerList` and entries from the SHA-256 verified CTD410
fatjar. Network players, proxy service boundaries and scheduling are mocked; the queue
algorithm and native policy are not reimplemented in the tests. Transfer assertions
invoke CTD's actual pinned `runTransfer` method.

## RED/GREEN evidence

Initial focused command:

```sh
./gradlew --no-daemon test --tests '*ReconnectHandlerTest' \
  -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar
```

The tests compiled and ran: `10 tests completed, 2 failed`. The intended immediate queue
test failed with Mockito `WantedButNotInvoked` for `QueueManager.queue`; the paused test
reached the old ping path. The standalone handler therefore demonstrably lacked native
queue ownership before implementation.

A later explicit closing-executor reproducer ran before its fix:
`--tests '*ReconnectHandlerTest.rejectedRecoveryTurnClosesTheGuard'` produced
`1 test completed, 1 failed`, with an unexpected `RejectedExecutionException` escaping
the recovery turn. Catching rejection now closes the handler and removes its registry entry.

Review regressions compiled and executed as `45 tests completed, 6 failed`: managed
server-full kicks, paused server listing/denied selection, global-disable transitions
and replacement-manager entry adoption failed before their fixes. The snapshot race
reproducer then failed with `NullPointerException` when native enabled state changed
between two reads. Each operation now uses one local snapshot and retains neither a
manager nor queue field. A separate kick-inspection race test failed before applying
the same single-lookup decision to kick recovery.

Removed-target regressions executed as `2 tests completed, 2 failed`: unknown queue
lookup propagated `IllegalArgumentException`, while the standalone fallback kept pinging.
Both now close the controller and disconnect using CTD's existing translated message.
A final enabled-check/manager-shutdown reproducer executed as `1 test completed,
1 failed` with an unexpected `IllegalStateException`. Manager acquisition and queue
lookup now share the expected unavailable-state catch, preserving owned handoff recovery.

Final verification used JDK 25, the verified CTD 410 jar and the newly built paired API jar:

```sh
./gradlew --no-daemon build \
  -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar \
  -PlimboApiJar=/absolute/path/to/LimboAPI/api/build/libs/api-1.1.27-MCExp-CTD410.1.jar
```

Result: **BUILD SUCCESSFUL; 71 tests, zero failed/skipped**. Checkstyle, SpotBugs main
and tests, license validation, compilation and shaded-jar creation passed. Test runtime
puts CTD's provided classes first, matching the proxy environment and avoiding older
compile-only Adventure NBT classes shadowing CTD's runtime.

No checkpoint commits were made by the implementation subtask; final verified commits
are coordinated by the parent task. This report retains both executed RED/GREEN cycles.

## Security and lifecycle review

This post-login integration is entered from matching established-server restart kicks.
Connecting-server kicks are accepted only for a tracked, native-managed target entry
currently waiting for connection. CTD's own component matcher checks banned reasons,
including translatable keys. Prior callback changes and companion LimboAPI event
protection keep authentication/resource-pack refusal decisions authoritative.

Queue command aliases execute as the player through CTD's command manager and normal
permissions/events. The bridge exposes neither generic proxy commands nor pre-login
commands, and adds a 250 ms per-player throttle. It does not log chat credentials.
Only an explicit native leave command cancels an initial paused insertion; listing
servers and failed command permission checks do not cancel it.

The handler owns at most one native guard future per active reconnect session. Virtual
world release cancels limbo UI/ping/join tasks while preserving that guard. Physical
disconnect, backend connection, executor rejection, unrelated handler takeover and
proxy shutdown cancel it and release the plugin registry. The plugin never dequeues
entries on virtual-world release or alters native disconnect reservation timers.
If the native queue is disabled during a handoff, only the owned in-progress boolean
and original target remain as authorization metadata. Current banned reasons and
other plugins' results still apply. Recovery, success and close clear that metadata.
Replaced managers/queues are never retained: an existing replacement entry is adopted,
and a missing entry is never reinserted. Disabling the queue switches an active limbo
to standalone reconnect, or first recovers a released limbo after native completion.

## Limits

Whole-project coverage percentage was not measured. The current tests verify native
queue algorithms/policies and the plugin's lifecycle decisions; they do not constitute
a real client end-to-end PLAY restart/reconnect test or a Redis multi-proxy deployment.
The previously documented ForcePack/nLogin smoke is historical and separate from these
new queue tests. Premium/password authentication, resource-pack rendering, an actual
backend restart and cross-proxy queue transfer still require staging verification.

When a native entry is removed or rejected, an active player with no backend remains
in limbo and can explicitly select a queue/server or disconnect. Automatic reconnect
insertion never restarts merely because the entry disappeared. No production server
was modified during development or verification.
If the target registration is removed/replaced, the plugin closes its controller and
disconnects using CTD's server-does-not-exist translation; it never pings the obsolete target.
