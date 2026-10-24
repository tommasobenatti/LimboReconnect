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

import com.velocityctd.api.queue.Queue;
import com.velocityctd.api.queue.QueueEntry;
import com.velocityctd.api.queue.QueueManager;
import com.velocityctd.api.queue.QueueState;
import com.velocityctd.proxy.util.ComponentUtils;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import java.util.List;
import java.util.Locale;
import net.kyori.adventure.text.Component;

/** Keeps CTD's own entries and policy authoritative; it never reconstructs or removes an entry. */
final class CtdQueueSupport {

  private final ProxyServer proxy;
  private final Player player;
  private final RegisteredServer target;
  private boolean attempted;
  private volatile boolean targetRemoved;

  private CtdQueueSupport(ProxyServer proxy, Player player, RegisteredServer target) {
    this.proxy = proxy;
    this.player = player;
    this.target = target;
  }

  static CtdQueueSupport create(ProxyServer proxy, Player player, RegisteredServer target) {
    if (!CONFIG.integrateCtdQueue || !proxy.isQueueEnabled() || player.hasPermission("velocity.queue.bypass")
        || !(proxy.getConfiguration() instanceof VelocityConfiguration config)
        || config.getQueue().getNoQueueServers().contains(target.getServerInfo().getName())) {
      return null;
    }
    CtdQueueSupport support = new CtdQueueSupport(proxy, player, target);
    QueueContext context = support.context();
    if (context == null) {
      return support.targetRemoved ? support : null;
    }
    if (context.queue().getState() == QueueState.INACTIVE) {
      return null;
    }
    return support;
  }

  synchronized void tryEnqueue() {
    QueueContext context = this.context();
    if (context == null || this.attempted) {
      return;
    }
    Queue queue = context.queue();
    QueueManager manager = context.manager();
    if (queue.contains(this.player.getUniqueId())) {
      this.attempted = true;
      return;
    }
    VelocityConfiguration.Queue config = this.configuration();
    if (!this.proxy.isQueueEnabled() || this.player.hasPermission("velocity.queue.bypass")
        || queue.getState() == QueueState.INACTIVE
        || config.getNoQueueServers().contains(this.target.getServerInfo().getName())) {
      this.attempted = true;
      return;
    }
    // Never swap an existing user-selected queue merely because the old backend restarted.
    if (!config.isAllowMultiQueue() && manager.getQueueFor(this.player.getUniqueId()) != null) {
      this.attempted = true;
      return;
    }
    if (queue.getState() == QueueState.PAUSED && !config.isAllowPausedQueueJoining()) {
      return;
    }
    this.attempted = true;
    // CTD performs the protocol check, priority calculation, Redis/FIFO insertion and optional queue-server transfer.
    // Failed eligibility, explicit leave, max retries and banned reasons must never trigger another insertion here.
    manager.queue(this.player, this.target);
  }

  QueueEntry entry() {
    QueueContext context = this.context();
    return context == null ? null : context.queue().getEntry(this.player.getUniqueId());
  }

  boolean refresh() {
    return this.context() != null;
  }

  boolean isTargetRemoved() {
    return this.targetRemoved;
  }

  private QueueContext context() {
    if (this.targetRemoved || this.proxy.getServer(this.target.getServerInfo().getName()).filter(this.target::equals).isEmpty()) {
      this.targetRemoved = true;
      return null;
    }
    if (!CONFIG.integrateCtdQueue || !this.proxy.isQueueEnabled()) {
      return null;
    }
    QueueManager currentManager;
    Queue currentQueue;
    try {
      currentManager = this.proxy.getQueueManager();
      currentQueue = currentManager == null ? null : currentManager.getQueue(this.target.getServerInfo().getName());
    } catch (IllegalStateException exception) {
      // Queue shutdown can race with the preceding enabled check.
      return null;
    } catch (IllegalArgumentException exception) {
      // CTD rejects unknown backends; this can race with a native configuration reload.
      this.targetRemoved = true;
      return null;
    }
    // A reload can replace either object. Each operation gets one local snapshot; neither reference is retained.
    return currentQueue == null ? null : new QueueContext(currentManager, currentQueue);
  }

  boolean waitingForConnection() {
    QueueContext context = this.context();
    if (context == null) {
      return false;
    }
    QueueEntry targetEntry = context.queue().getEntry(this.player.getUniqueId());
    return (targetEntry != null && targetEntry.isWaitingForConnection())
        || context.manager().getQueues().stream().anyMatch(candidate -> {
          QueueEntry entry = candidate.getEntry(this.player.getUniqueId());
          return entry != null && entry.isWaitingForConnection();
        });
  }

  boolean canRecoverKick(Component reason, boolean ownedHandoff) {
    QueueContext context = this.context();
    if (!allowsKickReason(this.proxy, reason)) {
      return false;
    }
    if (context == null) {
      return !this.targetRemoved && ownedHandoff;
    }
    QueueEntry entry = context.queue().getEntry(this.player.getUniqueId());
    return entry != null && entry.isWaitingForConnection();
  }

  boolean isTargetConnectionPending() {
    QueueEntry entry = this.entry();
    return entry != null && entry.isWaitingForConnection();
  }

  static boolean allowsKickReason(ProxyServer proxy, Component reason) {
    return proxy.getConfiguration() instanceof VelocityConfiguration config && config.getQueue().getBannedReason().stream()
        .noneMatch(banned -> ComponentUtils.containsString(reason, banned));
  }

  synchronized void cancelAutomaticEnqueue() {
    this.attempted = true;
  }

  boolean acceptsCommand(String label) {
    VelocityConfiguration config = (VelocityConfiguration) this.proxy.getConfiguration();
    return matchesAlias(config.getServerAliases(), label) || label.equals("server")
        || this.isLeaveCommand(label);
  }

  boolean isLeaveCommand(String label) {
    List<String> leaveAliases = this.configuration().getLeaveQueueAliases();
    return leaveAliases.isEmpty() ? label.equals("leavequeue") : matchesAlias(leaveAliases, label);
  }

  private VelocityConfiguration.Queue configuration() {
    return ((VelocityConfiguration) this.proxy.getConfiguration()).getQueue();
  }

  private static boolean matchesAlias(List<String> aliases, String label) {
    return aliases.stream().anyMatch(alias -> alias.toLowerCase(Locale.ROOT).equals(label));
  }

  private record QueueContext(QueueManager manager, Queue queue) {
  }
}
