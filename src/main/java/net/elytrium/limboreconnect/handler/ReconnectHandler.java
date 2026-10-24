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
import java.time.Duration;
import java.util.List;
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
import net.kyori.adventure.title.Title;

public class ReconnectHandler implements LimboSessionHandler {

  private final PingOptions pingOptions = PingOptions.builder().timeout(Duration.ofMillis(Math.max(1, CONFIG.pingTimeout))).build();
  private final RegisteredServer server;
  private final List<Title> offlineTitles;
  private final List<Title> connectingTitles;
  private final long checkInterval = Math.max(1, CONFIG.checkInterval);
  private final long joinDelay = Math.max(0, CONFIG.joinDelay);
  private final long messageInterval = Math.clamp(CONFIG.messages.titles.showDelay, 1, Long.MAX_VALUE / 50) * 50;
  private LimboPlayer player;
  private volatile boolean connected = true;
  private boolean connecting = false;
  private int titleIndex = -1;
  private final PlaySound waitSound;
  private final PlaySound connectSound;
  private ScheduledFuture<?> pingTask;
  private ScheduledFuture<?> messageTask;
  private ScheduledFuture<?> reconnectTask;
  private CompletableFuture<?> pendingPing;

  public ReconnectHandler(LimboReconnect plugin, RegisteredServer server) {
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
    this.player = player;
    this.player.disableFalling();
    this.player.setGameMode(CONFIG.world.gamemode);
    this.schedulePing();
    this.tickMessages();
  }

  @Override
  public void onDisconnect() {
    this.connected = false;
    cancel(this.pingTask);
    cancel(this.messageTask);
    cancel(this.reconnectTask);
    if (this.pendingPing != null) {
      this.pendingPing.cancel(false);
      this.pendingPing = null;
    }
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
