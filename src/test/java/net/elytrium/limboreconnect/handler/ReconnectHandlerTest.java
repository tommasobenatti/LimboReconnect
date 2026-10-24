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
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.player.LimboPlayer;
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
  private final Player proxyPlayer = mock(Player.class);
  private final RegisteredServer backend = mock(RegisteredServer.class);
  private LimboReconnect plugin;

  @BeforeEach
  void setUp() {
    ProxyServer proxy = mock(ProxyServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    PluginContainer container = mock(PluginContainer.class);
    doReturn(Optional.of(mock(LimboFactory.class))).when(container).getInstance();
    when(proxy.getPluginManager().getPlugin("limboapi")).thenReturn(Optional.of(container));
    this.plugin = new LimboReconnect(mock(Logger.class), proxy, Path.of("unused-test-directory"));
    this.plugin.offlineTitles.add(Title.title(Component.text("Waiting"), Component.empty()));
    this.plugin.connectingTitles.add(Title.title(Component.text("Connecting"), Component.empty()));
    when(this.player.getScheduledExecutor()).thenReturn(this.executor);
    when(this.player.getProxyPlayer()).thenReturn(this.proxyPlayer);
    when(this.proxyPlayer.isActive()).thenReturn(true);
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
}
