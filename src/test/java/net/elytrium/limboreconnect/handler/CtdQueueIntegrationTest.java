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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocityctd.api.queue.QueueEntry;
import com.velocityctd.api.queue.QueueState;
import com.velocityctd.api.queue.ServerStatus;
import com.velocityctd.proxy.queue.VelocityQueue;
import com.velocityctd.proxy.queue.VelocityQueueManager;
import com.velocitypowered.api.proxy.server.ServerInfo;
import com.velocitypowered.proxy.VelocityServer;
import com.velocitypowered.proxy.config.VelocityConfiguration;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.server.VelocityRegisteredServer;
import java.lang.reflect.Method;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import net.elytrium.limboreconnect.Config;
import net.elytrium.limboreconnect.LimboReconnect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Exercises the actual manager, LocalVelocityQueue and entries from the pinned CTD 410 jar. */
class CtdQueueIntegrationTest {

  private final VelocityServer proxy = mock(VelocityServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
  private final VelocityRegisteredServer backend = mock(VelocityRegisteredServer.class);
  private VelocityQueueManager manager;
  private VelocityQueue<?> queue;

  @BeforeEach
  void setUp() {
    resetConfig();
    VelocityConfiguration config = this.proxy.getConfiguration();
    when(config.getQueue().isEnabled()).thenReturn(true);
    when(config.getQueue().getQueueServer()).thenReturn("");
    when(config.getQueue().getNoQueueServers()).thenReturn(List.of());
    when(this.backend.getServerInfo()).thenReturn(new ServerInfo("openworld", new InetSocketAddress("127.0.0.1", 25566)));
    when(this.proxy.getAllServers()).thenReturn(List.of(this.backend));
    when(this.proxy.getServer("openworld")).thenReturn(Optional.of(this.backend));
    when(this.proxy.isQueueEnabled()).thenReturn(true);
    this.manager = new VelocityQueueManager(this.proxy);
    when(this.proxy.getQueueManager()).thenReturn(this.manager);
    this.queue = this.manager.getQueue("openworld");
  }

  @Test
  void nativeManagerPreservesPriorityFifoAndExistingEntryIdentity() {
    ConnectedPlayer first = this.player("First", 10);
    ConnectedPlayer reconnecting = this.player("Reconnect", 5);
    final ConnectedPlayer last = this.player("Last", 5);
    this.manager.queue(first, this.backend);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, reconnecting, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    this.manager.queue(last, this.backend);
    QueueEntry original = this.queue.getEntry(reconnecting.getUniqueId());

    CtdQueueSupport secondSession = CtdQueueSupport.create(this.proxy, reconnecting, this.backend);
    assertNotNull(secondSession);
    secondSession.tryEnqueue();

    assertSame(original, this.queue.getEntry(reconnecting.getUniqueId()));
    assertEquals(5, original.getPriority());
    assertEquals(List.of(first.getUniqueId(), reconnecting.getUniqueId(), last.getUniqueId()),
        this.queue.getEntries().stream().map(QueueEntry::getUniqueId).toList());
    assertEquals(3, this.queue.size());
    verify(reconnecting, never()).createConnectionRequest(any());
  }

  @Test
  void offlineWarmupFullAndPausedRemainUnderNativeTransferPolicy() throws Exception {
    ConnectedPlayer player = this.player("Reconnect", 5);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    Method transfer = VelocityQueueManager.class.getDeclaredMethod("runTransfer");
    transfer.setAccessible(true);
    for (ServerStatus status : List.of(ServerStatus.OFFLINE, ServerStatus.WAITING, ServerStatus.FULL)) {
      this.queue.setServerStatus(status);
      transfer.invoke(this.manager);
    }
    this.queue.setState(QueueState.PAUSED);
    this.queue.setServerStatus(ServerStatus.ONLINE);
    transfer.invoke(this.manager);

    verify(player, never()).createConnectionRequest(any());
    assertFalse(this.queue.getEntry(player.getUniqueId()).isWaitingForConnection());
    assertTrue(this.queue.contains(player.getUniqueId()));
  }

  @Test
  void nativeVersionRejectionDoesNotLoopOrForceAConnection() {
    ConnectedPlayer player = this.player("Unsupported", 1);
    when(player.checkVersionCompatibility(this.backend)).thenReturn(false);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    when(player.checkVersionCompatibility(this.backend)).thenReturn(true);
    support.tryEnqueue();

    assertFalse(this.queue.contains(player.getUniqueId()));
    verify(player, never()).createConnectionRequest(any());
  }

  @Test
  void explicitRemovalNeverRecreatesTheNativeEntry() {
    ConnectedPlayer player = this.player("Leave", 1);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    this.queue.dequeue(player.getUniqueId());
    support.tryEnqueue();

    assertEquals(0, this.queue.size());
  }

  @Test
  void replacementManagerAdoptsOnlyItsExistingEntry() {
    ConnectedPlayer player = this.player("Reload", 3);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    final QueueEntry old = support.entry();
    VelocityQueueManager replacement = new VelocityQueueManager(this.proxy);
    when(this.proxy.getQueueManager()).thenReturn(replacement);
    replacement.queue(player, this.backend);
    QueueEntry replacementEntry = replacement.getQueue("openworld").getEntry(player.getUniqueId());

    support.tryEnqueue();

    assertSame(replacementEntry, support.entry());
    org.junit.jupiter.api.Assertions.assertNotSame(old, support.entry());
    assertEquals(1, replacement.getQueue("openworld").size());
  }

  @Test
  void replacementManagerDoesNotReinsertMissingEntry() {
    ConnectedPlayer player = this.player("RemovedReload", 3);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    this.queue.dequeue(player.getUniqueId());
    VelocityQueueManager replacement = new VelocityQueueManager(this.proxy);
    when(this.proxy.getQueueManager()).thenReturn(replacement);

    support.tryEnqueue();

    org.junit.jupiter.api.Assertions.assertNull(support.entry());
    assertEquals(0, replacement.getQueue("openworld").size());
  }

  @Test
  void disableBetweenQueueReadsCannotThrowOrAbandonTheGuard() {
    ConnectedPlayer player = this.player("DisableRace", 3);
    CtdQueueSupport support = CtdQueueSupport.create(this.proxy, player, this.backend);
    assertNotNull(support);
    support.tryEnqueue();
    when(this.proxy.isQueueEnabled()).thenReturn(true, false);

    assertFalse(support.waitingForConnection());
  }

  private ConnectedPlayer player(String name, int priority) {
    ConnectedPlayer player = mock(ConnectedPlayer.class);
    UUID id = UUID.randomUUID();
    when(player.getUniqueId()).thenReturn(id);
    when(player.getUsername()).thenReturn(name);
    when(player.getQueuePriority("openworld")).thenReturn(priority);
    when(player.isActive()).thenReturn(true);
    when(player.checkVersionCompatibility(this.backend)).thenReturn(true);
    when(this.proxy.getPlayer(id)).thenReturn(Optional.of(player));
    return player;
  }

  private static void resetConfig() {
    LimboReconnect.CONFIG = new Config();
  }
}
