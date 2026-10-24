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

import static net.elytrium.limboreconnect.LimboReconnect.CONFIG;

import com.velocitypowered.api.proxy.server.PingOptions;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboSessionHandler;
import net.elytrium.limboapi.api.player.LimboPlayer;
import net.elytrium.limboreconnect.Config;
import net.elytrium.limboreconnect.LimboReconnect;
import net.elytrium.limboreconnect.protocol.packets.PlaySound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;

public class ReconnectHandler implements LimboSessionHandler {

  private static final long QUEUE_GUARD_INTERVAL = 250;
  private final LimboReconnect plugin;
  private final PingOptions pingOptions = PingOptions.builder().timeout(Duration.ofMillis(Math.max(1, CONFIG.pingTimeout))).build();
  private final RegisteredServer server;
  private final List<Title> offlineTitles;
  private final List<Title> connectingTitles;
  private final long checkInterval = Math.max(1, CONFIG.checkInterval);
  private final long joinDelay = Math.max(0, CONFIG.joinDelay);
  private final long messageInterval = Math.clamp(CONFIG.messages.titles.showDelay, 1, Long.MAX_VALUE / 50) * 50;
  private volatile LimboPlayer player;
  private volatile boolean connected = true;
  private boolean connecting = false;
  private int titleIndex = -1;
  private final PlaySound waitSound;
  private final PlaySound connectSound;
  private ScheduledFuture<?> pingTask;
  private ScheduledFuture<?> messageTask;
  private ScheduledFuture<?> reconnectTask;
  private CompletableFuture<?> pendingPing;
  private volatile CtdQueueSupport nativeQueue;
  private boolean initialized;
  private volatile boolean closed;
  private ScheduledFuture<?> queueTask;
  private boolean recoveryPending;
  private volatile boolean detachedNativeHandoff;
  private long lastCommand;

  public ReconnectHandler(LimboReconnect plugin, RegisteredServer server) {
    this.plugin = plugin;
    this.server = server;
    this.offlineTitles = List.copyOf(plugin.offlineTitles);
    this.connectingTitles = List.copyOf(plugin.connectingTitles);
    Config.Sounds sounds = CONFIG.sounds;
    double playerX = CONFIG.world.playerCoords.x;
    double playerY = CONFIG.world.playerCoords.y;
    double playerZ = CONFIG.world.playerCoords.z;
    this.waitSound = new PlaySound(sounds.waiting.name, playerX, playerY, playerZ, sounds.waiting.volume, sounds.waiting.pitch);
    this.connectSound = new PlaySound(sounds.connecting.name, playerX, playerY, playerZ, sounds.connecting.volume, sounds.connecting.pitch);
  }

  @Override
  public void onSpawn(Limbo server, LimboPlayer player) {
    if (this.closed) {
      return;
    }
    this.player = player;
    this.connected = true;
    this.recoveryPending = false;
    this.detachedNativeHandoff = false;
    this.plugin.trackHandler(player.getProxyPlayer(), this);
    this.player.disableFalling();
    this.player.setGameMode(CONFIG.world.gamemode);
    if (!this.initialized) {
      this.initialized = true;
      this.nativeQueue = CtdQueueSupport.create(this.plugin.getServer(), player.getProxyPlayer(), this.server);
    }
    if (this.nativeQueue == null) {
      cancel(this.queueTask);
      this.queueTask = null;
      this.schedulePing();
    } else {
      this.nativeQueue.tryEnqueue();
      this.scheduleQueueGuard();
    }
    this.tickMessages();
  }

  @Override
  public void onDisconnect() {
    if (this.closed) {
      return;
    }
    this.connected = false;
    CtdQueueSupport queue = this.nativeQueue;
    if (queue != null && queue.isTargetConnectionPending()) {
      this.detachedNativeHandoff = true;
    }
    this.stopLimboTasks();
    if ((this.nativeQueue == null && !this.detachedNativeHandoff) || !this.player.getProxyPlayer().isActive()) {
      this.close();
    }
  }

  private void stopLimboTasks() {
    cancel(this.pingTask);
    cancel(this.messageTask);
    cancel(this.reconnectTask);
    this.pingTask = null;
    this.messageTask = null;
    this.reconnectTask = null;
    if (this.pendingPing != null) {
      this.pendingPing.cancel(false);
      this.pendingPing = null;
    }
  }

  /** A limbo release during a native connection is not a physical player disconnect. */
  public void close() {
    this.closed = true;
    this.connected = false;
    this.detachedNativeHandoff = false;
    this.stopLimboTasks();
    cancel(this.queueTask);
    this.queueTask = null;
    if (this.player != null) {
      this.plugin.removeHandler(this.player.getProxyPlayer(), this);
    }
  }

  public boolean isNativeQueueTarget(RegisteredServer target) {
    return !this.closed && (this.nativeQueue != null || this.detachedNativeHandoff) && this.server.equals(target);
  }

  public boolean canRecoverQueueKick(RegisteredServer target, Component reason) {
    if (!this.isNativeQueueTarget(target)) {
      return false;
    }
    CtdQueueSupport queue = this.nativeQueue;
    if (queue != null) {
      return queue.canRecoverKick(reason, this.detachedNativeHandoff && !this.connected);
    }
    return this.detachedNativeHandoff && !this.connected && CtdQueueSupport.allowsKickReason(this.plugin.getServer(), reason);
  }

  @Override
  public void onChat(String chat) {
    CtdQueueSupport queue = this.nativeQueue;
    if (!this.connected || this.closed || queue == null || !chat.startsWith("/")) {
      return;
    }
    String command = chat.substring(1).strip();
    String label = command.split("\\s+", 2)[0].toLowerCase(Locale.ROOT);
    long now = System.nanoTime();
    if (!queue.acceptsCommand(label) || (this.lastCommand != 0 && now - this.lastCommand < TimeUnit.MILLISECONDS.toNanos(250))) {
      return;
    }
    this.lastCommand = now;
    if (queue.isLeaveCommand(label)) {
      queue.cancelAutomaticEnqueue();
    }
    this.plugin.getServer().getCommandManager().executeAsync(this.player.getProxyPlayer(), command)
        .exceptionally(exception -> {
          LimboReconnect.getLogger().warn("Unable to execute a native queue command", exception);
          return false;
        });
  }

  private void scheduleQueueGuard() {
    if (this.closed || this.queueTask != null) {
      return;
    }
    try {
      this.queueTask = this.player.getScheduledExecutor().schedule(this::tickQueue, QUEUE_GUARD_INTERVAL, TimeUnit.MILLISECONDS);
    } catch (RejectedExecutionException exception) {
      this.close();
    }
  }

  private void tickQueue() {
    this.queueTask = null;
    if (this.closed) {
      return;
    }
    if (!this.player.getProxyPlayer().isActive() || this.player.getProxyPlayer().getCurrentServer().isPresent()) {
      this.close();
      return;
    }
    CtdQueueSupport queue = this.nativeQueue;
    if (queue != null && !queue.refresh()) {
      if (queue.isTargetRemoved()) {
        this.disconnectRemovedTarget();
        return;
      }
      this.nativeQueue = null;
      queue = null;
    }
    if (this.connected && queue == null) {
      if (this.pingTask == null) {
        this.schedulePing();
      }
      return;
    }
    if (this.connected) {
      queue.tryEnqueue();
      this.connecting = queue.waitingForConnection();
    } else if (this.player.getProxyPlayer() instanceof ConnectedPlayer proxyPlayer && proxyPlayer.getConnectionInFlight() != null) {
      this.scheduleQueueGuard();
      return;
    } else if (!this.player.isServerConnectionPrepared()) {
      this.close();
      return;
    } else if (!this.isWaitingForNativeConnection() && !this.recoveryPending) {
      // Native completion and CTD's fallback may race. Give the same event loop one turn to establish a fallback.
      this.recoveryPending = true;
      try {
        this.player.getScheduledExecutor().execute(() -> {
          this.recoveryPending = false;
          if (!this.closed && !this.connected && this.player.getProxyPlayer().isActive()
              && this.player.getProxyPlayer().getCurrentServer().isEmpty()
              && (!(this.player.getProxyPlayer() instanceof ConnectedPlayer proxyPlayer) || proxyPlayer.getConnectionInFlight() == null)
              && !this.isWaitingForNativeConnection() && this.player.isServerConnectionPrepared()) {
            this.plugin.recoverPlayer(this.player.getProxyPlayer(), this);
          }
        });
      } catch (RejectedExecutionException exception) {
        this.close();
        return;
      }
    }
    this.scheduleQueueGuard();
  }

  private boolean isWaitingForNativeConnection() {
    CtdQueueSupport queue = this.nativeQueue;
    return queue != null && queue.waitingForConnection();
  }

  private boolean isTargetRegistered() {
    return this.plugin.getServer().getServer(this.server.getServerInfo().getName()).filter(this.server::equals).isPresent();
  }

  private void disconnectRemovedTarget() {
    this.close();
    this.player.getProxyPlayer().disconnect(Component.translatable("velocity.command.server-does-not-exist",
        Component.text(this.server.getServerInfo().getName())));
  }

  @Override
  public void onMove(double x, double y, double z) {
    this.waitSound.setPosition(x, y, z);
    this.connectSound.setPosition(x, y, z);
  }

  private void tick() {
    if (!this.connected) {
      return;
    }
    if (!this.isTargetRegistered()) {
      this.disconnectRemovedTarget();
      return;
    }

    try {
      this.pendingPing = this.server.ping(this.pingOptions);
    } catch (RuntimeException exception) {
      this.handlePing(exception);
      return;
    }
    this.pendingPing.whenComplete((ping, exception) -> {
      if (!this.connected) {
        return;
      }
      try {
        this.player.getScheduledExecutor().execute(() -> this.handlePing(exception));
      } catch (RejectedExecutionException rejected) {
        // The connection event loop can stop while a backend ping completes.
        this.onDisconnect();
      }
    });
  }

  private void handlePing(Throwable exception) {
    this.pendingPing = null;
    if (!this.connected || !this.player.getProxyPlayer().isActive()) {
      this.onDisconnect();
      return;
    }
    if (exception != null) {
      if (CONFIG.debug) {
        LimboReconnect.getLogger()
            .info("{} can't ping {}", this.player.getProxyPlayer().getUsername(), this.server.getServerInfo().getName());
      }
      this.schedulePing();
    } else {
      this.connecting = true;
      this.titleIndex = -1;
      this.reconnectTask = this.player.getScheduledExecutor().schedule(() -> {
        if (this.connected && this.player.getProxyPlayer().isActive()) {
          if (!this.isTargetRegistered()) {
            this.disconnectRemovedTarget();
            return;
          }
          this.player.writePacket(this.connectSound);
          this.player.getProxyPlayer().resetTitle();
          this.onDisconnect();
          this.player.disconnect(this.server);
        }
      }, this.joinDelay, TimeUnit.MILLISECONDS);
    }
  }

  private void schedulePing() {
    this.pingTask = this.player.getScheduledExecutor().schedule(this::tick, this.checkInterval, TimeUnit.MILLISECONDS);
  }

  private void tickMessages() {
    if (!this.connected) {
      return;
    }

    List<Title> titles = this.connecting ? this.connectingTitles : this.offlineTitles;
    if (!titles.isEmpty()) {
      this.titleIndex = (this.titleIndex + 1) % titles.size();
      this.player.getProxyPlayer().showTitle(titles.get(this.titleIndex));
    }
    this.player.writePacket(this.waitSound);
    this.messageTask = this.player.getScheduledExecutor().schedule(this::tickMessages, this.messageInterval, TimeUnit.MILLISECONDS);
  }

  private static void cancel(ScheduledFuture<?> task) {
    if (task != null) {
      task.cancel(false);
    }
  }
}
