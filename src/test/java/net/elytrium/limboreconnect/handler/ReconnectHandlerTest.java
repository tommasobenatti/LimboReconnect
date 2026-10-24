/*
 * Copyright (C) 2022 - 2025 Elytrium
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package net.elytrium.limboreconnect.handler;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocityctd.api.queue.Queue;
import com.velocityctd.api.queue.QueueEntry;
import com.velocityctd.api.queue.QueueManager;
import com.velocityctd.api.queue.QueueState;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.api.proxy.server.ServerPing;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.backend.VelocityServerConnection;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboreconnect.Config;
import net.elytrium.limboreconnect.LimboReconnect;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;

class ReconnectHandlerTest {

  private final List<Runnable> scheduled = new ArrayList<>();
  private final List<ScheduledFuture<?>> futures = new ArrayList<>();
  private final List<Runnable> immediate = new ArrayList<>();
  private final ScheduledExecutorService executor = mock(ScheduledExecutorService.class);
  private final LimboPlayer player = mock(LimboPlayer.class);
  private final Player proxyPlayer = mock(ConnectedPlayer.class);
  private final RegisteredServer backend = mock(RegisteredServer.class);
  private final ProxyServer proxy = mock(ProxyServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
  private final QueueManager queues = mock(QueueManager.class);
  private final Queue queue = mock(Queue.class);
  private LimboReconnect plugin;

  @BeforeEach
  void setUp() {
    resetConfig();
    PluginContainer container = mock(PluginContainer.class);
    doReturn(Optional.of(mock(LimboFactory.class))).when(container).getInstance();
    when(this.proxy.getPluginManager().getPlugin("limboapi")).thenReturn(Optional.of(container));
    this.plugin = spy(new LimboReconnect(mock(Logger.class), this.proxy, Path.of("unused-test-directory")));
    doNothing().when(this.plugin).recoverPlayer(any(), any());
    this.plugin.offlineTitles.add(Title.title(Component.text("Waiting"), Component.empty()));
    this.plugin.connectingTitles.add(Title.title(Component.text("Connecting"), Component.empty()));
    when(this.player.getScheduledExecutor()).thenReturn(this.executor);
    when(this.player.getProxyPlayer()).thenReturn(this.proxyPlayer);
    when(this.proxyPlayer.isActive()).thenReturn(true);
    when(this.proxyPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
    ServerInfo info = mock(ServerInfo.class);
    when(info.getName()).thenReturn("openworld");
    when(this.backend.getServerInfo()).thenReturn(info);
    doReturn(Optional.of(this.backend)).when(this.proxy).getServer("openworld");
    doAnswer(invocation -> {
      this.scheduled.add(invocation.getArgument(0));
      ScheduledFuture<?> future = mock(ScheduledFuture.class);
      this.futures.add(future);
      return future;
    }).when(this.executor).schedule(any(Runnable.class), anyLong(), eq(TimeUnit.MILLISECONDS));
    doAnswer(invocation -> {
      this.immediate.add(invocation.getArgument(0));
      return null;
    }).when(this.executor).execute(any(Runnable.class));
  }

  @Test
  void nativeQueueTakesOwnershipImmediatelyWithoutPinging() {
    this.enableQueue(QueueState.ACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);

    handler.onSpawn(null, this.player);

    verify(this.queues).queue(this.proxyPlayer, this.backend);
    this.scheduled.getFirst().run();
    verify(this.backend, never()).ping(any(PingOptions.class));
    verify(this.player, never()).disconnect(any(RegisteredServer.class));
  }

  @Test
  void existingNativeQueueEntryIsPreserved() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.queue.contains(this.proxyPlayer.getUniqueId())).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);

    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    verify(this.queue, never()).dequeue(any(UUID.class));
  }

  @Test
  void pausedQueueWaitsUntilJoiningIsAllowed() {
    this.enableQueue(QueueState.PAUSED, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    verify(this.queues, never()).queue(any(), any());
    verify(this.backend, never()).ping(any(PingOptions.class));

    when(this.queue.getState()).thenReturn(QueueState.ACTIVE);
    this.scheduled.getFirst().run();

    verify(this.queues).queue(this.proxyPlayer, this.backend);
  }

  @Test
  void bypassUsesStandalonePingWithoutNativeImmediateConnect() {
    this.enableQueue(QueueState.ACTIVE, true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    assertEquals(2, this.scheduled.size());
  }

  @Test
  void inactiveQueueUsesStandalonePing() {
    this.enableQueue(QueueState.INACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    assertEquals(2, this.scheduled.size());
  }

  @Test
  void existingOtherQueueIsNeverSwappedAutomatically() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.queues.getQueueFor(this.proxyPlayer.getUniqueId())).thenReturn(mock(Queue.class));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    verify(this.backend, never()).ping(any(PingOptions.class));
  }

  @Test
  void nativeHandoffGuardWaitsForNativeFailureBeforeRestoringLimbo() {
    this.enableQueue(QueueState.ACTIVE, false);
    QueueEntry entry = mock(QueueEntry.class);
    when(this.queue.getEntry(this.proxyPlayer.getUniqueId())).thenReturn(entry);
    when(entry.isWaitingForConnection()).thenReturn(true);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    this.scheduled.getFirst().run();
    this.drainImmediate();
    verify(this.plugin, never()).recoverPlayer(any(), any());

    when(entry.isWaitingForConnection()).thenReturn(false);
    this.scheduled.getLast().run();
    this.drainImmediate();
    verify(this.plugin).recoverPlayer(this.proxyPlayer, handler);
    verify(this.queues, times(1)).queue(this.proxyPlayer, this.backend);
    verify(this.queue, never()).dequeue(any(UUID.class));
  }

  @Test
  void removedQueueEntryRestoresLimboWithoutReaddingPlayer() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    this.scheduled.getFirst().run();
    this.drainImmediate();
    handler.onSpawn(null, this.player);

    verify(this.plugin).recoverPlayer(this.proxyPlayer, handler);
    verify(this.queues, times(1)).queue(this.proxyPlayer, this.backend);
    verify(this.backend, never()).ping(any(PingOptions.class));
  }

  @Test
  void unrelatedSessionHandlerStopsRecoveryGuard() {
    this.enableQueue(QueueState.ACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    this.scheduled.getFirst().run();
    this.drainImmediate();

    verify(this.plugin, never()).recoverPlayer(any(), any());
    verify(this.plugin).removeHandler(this.proxyPlayer, handler);
  }

  @Test
  void physicalDisconnectCancelsAllNativeGuardTasksWithoutRemovingEntry() {
    this.enableQueue(QueueState.ACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    when(this.proxyPlayer.isActive()).thenReturn(false);
    handler.onDisconnect();

    this.futures.forEach(future -> verify(future).cancel(false));
    verify(this.queues, never()).removePlayerEntirely(any(UUID.class));
  }

  @Test
  void onlyConfiguredNativeQueueCommandsAreForwarded() {
    this.enableQueue(QueueState.ACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onChat("/login password");
    handler.onChat("/velocity shutdown");
    handler.onChat("/leavequeue");

    verify(this.proxy.getCommandManager()).executeAsync(this.proxyPlayer, "leavequeue");
    verify(this.proxy.getCommandManager(), times(1)).executeAsync(any(), any());
  }

  @Test
  void leavingWhilePausedPreventsDelayedAutomaticEnqueue() {
    this.enableQueue(QueueState.PAUSED, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onChat("/leavequeue");
    when(this.queue.getState()).thenReturn(QueueState.ACTIVE);
    this.scheduled.getFirst().run();

    verify(this.queues, never()).queue(any(), any());
  }

  @Test
  void listingServersWhilePausedDoesNotCancelAutomaticEnqueue() {
    this.enableQueue(QueueState.PAUSED, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onChat("/server");
    when(this.queue.getState()).thenReturn(QueueState.ACTIVE);
    this.scheduled.getFirst().run();

    verify(this.queues).queue(this.proxyPlayer, this.backend);
  }

  @Test
  void deniedServerSelectionWhilePausedDoesNotCancelAutomaticEnqueue() {
    this.enableQueue(QueueState.PAUSED, false);
    when(this.proxy.getCommandManager().executeAsync(this.proxyPlayer, "server private"))
        .thenReturn(CompletableFuture.completedFuture(false));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onChat("/server private");
    when(this.queue.getState()).thenReturn(QueueState.ACTIVE);
    this.scheduled.getFirst().run();

    verify(this.queues).queue(this.proxyPlayer, this.backend);
  }

  @Test
  void globalQueueDisableTransitionsActiveLimboToStandalonePing() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.backend.ping(any(PingOptions.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    when(this.proxy.isQueueEnabled()).thenReturn(false);
    this.scheduled.getFirst().run();
    this.scheduled.getLast().run();

    verify(this.backend).ping(any(PingOptions.class));
    verify(this.queues, times(1)).queue(this.proxyPlayer, this.backend);
  }

  @Test
  void globalQueueDisableDuringHandoffRecoversThenUsesStandalonePing() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    when(this.proxy.isQueueEnabled()).thenReturn(false);
    this.scheduled.getFirst().run();
    this.drainImmediate();
    verify(this.plugin).recoverPlayer(this.proxyPlayer, handler);

    int nextTask = this.scheduled.size();
    handler.onSpawn(null, this.player);
    when(this.backend.ping(any(PingOptions.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    this.scheduled.get(nextTask).run();

    verify(this.backend).ping(any(PingOptions.class));
  }

  @Test
  void globalQueueDisableRetainsOnlyOwnedHandoffAuthorizationUntilRecovery() {
    this.enableQueue(QueueState.ACTIVE, false);
    QueueEntry entry = mock(QueueEntry.class);
    when(this.queue.getEntry(this.proxyPlayer.getUniqueId())).thenReturn(entry);
    when(entry.isWaitingForConnection()).thenReturn(true);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    when(this.proxy.isQueueEnabled()).thenReturn(false);
    org.junit.jupiter.api.Assertions.assertTrue(handler.canRecoverQueueKick(this.backend, Component.text("Server is full")));
    this.scheduled.getFirst().run();

    org.junit.jupiter.api.Assertions.assertTrue(handler.canRecoverQueueKick(this.backend, Component.text("Server is full")));
    when(((VelocityConfiguration) this.proxy.getConfiguration()).getQueue().getBannedReason()).thenReturn(List.of("blocked"));
    org.junit.jupiter.api.Assertions.assertFalse(handler.canRecoverQueueKick(this.backend, Component.text("blocked")));
    this.drainImmediate();
    handler.onSpawn(null, this.player);
    org.junit.jupiter.api.Assertions.assertFalse(handler.canRecoverQueueKick(this.backend, Component.text("Server is full")));
  }

  @Test
  void nativeDisableDuringKickInspectionKeepsOwnedAuthorization() {
    this.enableQueue(QueueState.ACTIVE, false);
    QueueEntry entry = mock(QueueEntry.class);
    when(this.queue.getEntry(this.proxyPlayer.getUniqueId())).thenReturn(entry);
    when(entry.isWaitingForConnection()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    when(this.proxy.isQueueEnabled()).thenReturn(true, false);

    org.junit.jupiter.api.Assertions.assertTrue(handler.canRecoverQueueKick(this.backend, Component.text("Server is full")));
  }

  @Test
  void removedTargetQueueLookupTerminatesControllerWithoutAnOrphan() {
    this.enableQueue(QueueState.ACTIVE, false);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    when(this.queues.getQueue("openworld")).thenThrow(new IllegalArgumentException("Unknown server openworld"));

    assertDoesNotThrow(() -> this.scheduled.getFirst().run());

    verify(this.proxyPlayer).disconnect(any(Component.class));
    verify(this.plugin).removeHandler(this.proxyPlayer, handler);
    verify(this.backend, never()).ping(any(PingOptions.class));
  }

  @Test
  void unavailableManagerDuringReloadDoesNotAbandonTheGuard() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.backend.ping(any(PingOptions.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    when(this.proxy.getQueueManager()).thenThrow(new IllegalStateException("Queue is not enabled"));

    assertDoesNotThrow(() -> this.scheduled.getFirst().run());
    this.scheduled.getLast().run();

    verify(this.backend).ping(any(PingOptions.class));
  }

  @Test
  void standaloneFallbackNeverPingsAnUnregisteredTarget() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.backend.ping(any(PingOptions.class)))
        .thenReturn(CompletableFuture.failedFuture(new IllegalStateException("offline")));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    when(this.proxy.isQueueEnabled()).thenReturn(false);
    this.scheduled.getFirst().run();
    when(this.proxy.getServer("openworld")).thenReturn(Optional.empty());

    assertDoesNotThrow(() -> this.scheduled.getLast().run());

    verify(this.proxyPlayer).disconnect(any(Component.class));
    verify(this.backend, never()).ping(any(PingOptions.class));
  }

  @Test
  void configuredNativeAliasesAreForwarded() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(((VelocityConfiguration) this.proxy.getConfiguration()).getServerAliases()).thenReturn(List.of("fila"));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onChat("/fila openworld");

    verify(this.proxy.getCommandManager()).executeAsync(this.proxyPlayer, "fila openworld");
  }

  @Test
  void nativeBannedReasonRejectsManagedQueueKick() {
    this.enableQueue(QueueState.ACTIVE, false);
    QueueEntry entry = mock(QueueEntry.class);
    when(this.queue.getEntry(this.proxyPlayer.getUniqueId())).thenReturn(entry);
    when(entry.isWaitingForConnection()).thenReturn(true);
    when(((VelocityConfiguration) this.proxy.getConfiguration()).getQueue().getBannedReason()).thenReturn(List.of("whitelist"));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    org.junit.jupiter.api.Assertions.assertTrue(handler.canRecoverQueueKick(this.backend, Component.text("Server is restarting")));
    org.junit.jupiter.api.Assertions.assertFalse(
        handler.canRecoverQueueKick(this.backend, Component.translatable("multiplayer.disconnect.not_whitelisted")));
  }

  @Test
  void disabledIntegrationKeepsStandalonePing() {
    this.enableQueue(QueueState.ACTIVE, false);
    LimboReconnect.CONFIG.integrateCtdQueue = false;
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    assertEquals(2, this.scheduled.size());
  }

  @Test
  void noQueueBackendKeepsStandalonePing() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(((VelocityConfiguration) this.proxy.getConfiguration()).getQueue().getNoQueueServers()).thenReturn(List.of("openworld"));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    verify(this.queues, never()).queue(any(), any());
    assertEquals(2, this.scheduled.size());
  }

  @Test
  void inFlightFallbackPreventsLimboRecovery() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    when(((ConnectedPlayer) this.proxyPlayer).getConnectionInFlight()).thenReturn(mock(VelocityServerConnection.class));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    this.scheduled.getFirst().run();
    this.drainImmediate();

    verify(this.plugin, never()).recoverPlayer(any(), any());
  }

  @Test
  void rejectedRecoveryTurnClosesTheGuard() {
    this.enableQueue(QueueState.ACTIVE, false);
    when(this.player.isServerConnectionPrepared()).thenReturn(true);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();
    doThrow(new RejectedExecutionException("connection closed")).when(this.executor).execute(any(Runnable.class));

    assertDoesNotThrow(() -> this.scheduled.getFirst().run());
    verify(this.plugin).removeHandler(this.proxyPlayer, handler);
  }

  private void enableQueue(QueueState state, boolean bypass) {
    VelocityConfiguration config = mock(VelocityConfiguration.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    when(this.proxy.getConfiguration()).thenReturn(config);
    when(config.getQueue().getNoQueueServers()).thenReturn(List.of());
    when(config.getQueue().getBannedReason()).thenReturn(List.of());
    when(config.getQueue().getLeaveQueueAliases()).thenReturn(List.of());
    when(config.getServerAliases()).thenReturn(List.of("joinqueue", "queue"));
    when(this.proxy.getCommandManager().executeAsync(any(), any())).thenReturn(CompletableFuture.completedFuture(true));
    when(this.proxy.isQueueEnabled()).thenReturn(true);
    when(this.proxy.getQueueManager()).thenReturn(this.queues);
    when(this.proxyPlayer.getUniqueId()).thenReturn(UUID.randomUUID());
    when(this.proxyPlayer.hasPermission("velocity.queue.bypass")).thenReturn(bypass);
    ServerInfo info = mock(ServerInfo.class);
    when(info.getName()).thenReturn("openworld");
    when(this.backend.getServerInfo()).thenReturn(info);
    when(this.queues.getQueue("openworld")).thenReturn(this.queue);
    when(this.queue.getState()).thenReturn(state);
  }

  @Test
  void emptyTitleListsDisableTitlesWithoutStoppingReconnect() {
    this.plugin.offlineTitles.clear();
    this.plugin.connectingTitles.clear();
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);

    assertDoesNotThrow(() -> handler.onSpawn(null, this.player));
    assertEquals(2, this.scheduled.size());
    verify(this.proxyPlayer, never()).showTitle(any());
  }

  @Test
  void disconnectCancelsScheduledTasks() {
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    handler.onDisconnect();

    this.futures.forEach(future -> verify(future).cancel(false));
  }

  @Test
  void lateFailedPingDoesNotRescheduleAfterDisconnect() {
    CompletableFuture<ServerPing> ping = new CompletableFuture<>();
    when(this.backend.ping(any(PingOptions.class))).thenReturn(ping);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    this.scheduled.getFirst().run();
    handler.onDisconnect();
    ping.completeExceptionally(new IllegalStateException("offline"));
    this.drainImmediate();

    assertEquals(2, this.scheduled.size());
  }

  @Test
  void lateSuccessfulPingDoesNotScheduleJoinAfterDisconnect() {
    CompletableFuture<ServerPing> ping = new CompletableFuture<>();
    when(this.backend.ping(any(PingOptions.class))).thenReturn(ping);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    this.scheduled.getFirst().run();
    handler.onDisconnect();
    ping.complete(mock(ServerPing.class));
    this.drainImmediate();

    assertEquals(2, this.scheduled.size());
    verify(this.player, never()).disconnect(any(RegisteredServer.class));
  }

  @Test
  void delayedJoinDoesNotRunAfterDisconnectEvenWhenAlreadyQueued() {
    when(this.backend.ping(any(PingOptions.class)))
        .thenReturn(CompletableFuture.completedFuture(mock(ServerPing.class)));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    this.scheduled.getFirst().run();
    this.drainImmediate();
    assertEquals(3, this.scheduled.size());
    handler.onDisconnect();
    this.scheduled.getLast().run();

    verify(this.player, never()).disconnect(any(RegisteredServer.class));
  }

  @Test
  void pingFailureIsProcessedOnThePlayerExecutor() {
    CompletableFuture<ServerPing> ping = new CompletableFuture<>();
    when(this.backend.ping(any(PingOptions.class))).thenReturn(ping);
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);
    this.scheduled.getFirst().run();
    ping.completeExceptionally(new IllegalStateException("offline"));

    assertEquals(2, this.scheduled.size());
    assertEquals(1, this.immediate.size());
    this.drainImmediate();
    assertEquals(3, this.scheduled.size());
  }

  @Test
  void synchronousPingFailureDoesNotStopRetrying() {
    when(this.backend.ping(any(PingOptions.class))).thenThrow(new IllegalStateException("backend shutting down"));
    ReconnectHandler handler = new ReconnectHandler(this.plugin, this.backend);
    handler.onSpawn(null, this.player);

    assertDoesNotThrow(() -> this.scheduled.getFirst().run());
    assertEquals(3, this.scheduled.size());
  }

  private void drainImmediate() {
    while (!this.immediate.isEmpty()) {
      this.immediate.removeFirst().run();
    }
  }

  private static void resetConfig() {
    LimboReconnect.CONFIG = new Config();
  }
}
