# Velocity-CTD compatibility verification

Validated on 24 October 2026 with Java 25 and the official Velocity-CTD
`4.3.1-SNAPSHOT` build 410 fat JAR. Journeys were derived from the reported
login failure and the request to preserve ForcePack/nLogin behavior.

## Regression evidence

| Guarantee | Test | RED evidence | GREEN evidence |
| --- | --- | --- | --- |
| A disconnect cancels timers and the pending backend ping; late callbacks cannot reconnect or reschedule | `ReconnectHandlerTest` | Six initial lifecycle regressions failed against the original handler | All lifecycle tests pass |
| Empty administrator title lists disable titles safely | `ReconnectHandlerTest.emptyTitleListsDisableTitlesWithoutStoppingReconnect` | Original threw `ArithmeticException` | Pass |
| Immediate backend-ping exceptions continue retrying | `ReconnectHandlerTest.synchronousPingFailureDoesNotStopRetrying` | Original propagated `IllegalStateException` | Pass |
| Existing kick callbacks are retained and their result changes are honored | `ReconnectListenerTest` | Original discarded the prior callback | Pass |
| CTD's wrapped disconnect reason and unchanged fallback redirect still permit reconnect | `ReconnectListenerTest` | Verified against CTD's actual kick construction; an initial reason-equality hypothesis was rejected | Pass |
| Legacy sound pitch cannot overflow its unsigned byte | `PlaySoundTest.legacyHighPitchIsClampedBeforeWritingUnsignedByte` | Original wrapped 508 to 252 | Clamped to 255 |
| Reload disposes the replaced limbo | `LimboReconnectLifecycleTest.reloadDisposesReplacedLimbo` | Original never called `dispose` | Pass |
| Reload preserves administrator comments/unknown keys while adding missing defaults, then becomes idempotent | `LimboReconnectLifecycleTest.reloadAddsMissingDefaultsAndPreservesUnknownKeysAndComments` | Original rewrote away the comment/unknown key | Pass |
| Malformed YAML is not overwritten; malformed known types are rejected independently of unknown settings | `LimboReconnectLifecycleTest`, `ConfigCandidateTest` | Invalid boolean was silently accepted by the original typed reader | Pass |
| Private backups, atomic writes, concurrent-edit refusal, empty/edited collection preservation | `SafeConfigUpdaterTest` | Missing updater first failed the focused compile target | Pass |

## Final command and result

```sh
JAVA_HOME=/Library/Java/JavaVirtualMachines/amazon-corretto-25.jdk/Contents/Home ./gradlew build \
  -PvelocityCtdJar=/absolute/path/to/velocity-ctd-fatjar-4.3.1-SNAPSHOT-410.jar --no-daemon
```

Result: `BUILD SUCCESSFUL`; 34 tests passed, zero failed/skipped. Checkstyle,
SpotBugs (main and tests), license checks, compilation, and shaded JAR creation
passed. The shaded JAR includes its private SnakeYAML dependency and declares
LimboAPI as required; it includes neither Velocity nor test dependencies.

Kick-event result protection against other plugins is implemented and tested in
the companion LimboAPI patch. A separate final local smoke test exercised the
real ForcePack 1.3.75-SNAPSHOT and nLogin 2.0.18 jars with the companion patch:
one CONFIG socket held 36 seconds, pack statuses completed, two actual reloads
of each plugin preserved configuration hashes, and repeated logins succeeded.
Evidence: [the committed smoke summary](smoke-verification.md).
No production server or real Minecraft client was exercised. Pack rendering,
premium/password authentication and a full PLAY backend restart/reconnect cycle
remain staging tests. Coverage percentage was not measured.

The published GitHub release identifies the source commit and binary SHA-256.
No production deployment was performed. This report retains the RED/GREEN evidence.
