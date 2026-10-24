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

package net.elytrium.limboreconnect.listener;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.event.player.KickedFromServerEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import net.elytrium.limboapi.api.event.LoginLimboRegisterEvent;
import net.elytrium.limboreconnect.LimboReconnect;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ReconnectListenerTest {

  private final LimboReconnect plugin = mock(LimboReconnect.class);
  private final Player player = mock(Player.class);
  private final RegisteredServer server = mock(RegisteredServer.class);
  private final Component reason = Component.text("Server is restarting");
  private final LoginLimboRegisterEvent login = new LoginLimboRegisterEvent(this.player);

  @BeforeEach
  void setUp() {
    this.plugin.triggerMessage = Pattern.compile("Server is restarting");
    when(this.player.isActive()).thenReturn(true);
    LimboReconnect.CONFIG.requirePermission = false;
  }

  @Test
  void preservesPreviouslyRegisteredKickCallback() {
    AtomicInteger calls = new AtomicInteger();
    this.login.setOnKickCallback(event -> {
      calls.incrementAndGet();
      return true;
    });
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertTrue(this.login.getOnKickCallback().apply(this.kick(false)));
    org.junit.jupiter.api.Assertions.assertEquals(1, calls.get());
    verify(this.plugin, never()).addPlayer(any(), any());
  }

  @Test
  void doesNotOverridePreviousCallbacksRedirect() {
    this.login.setOnKickCallback(event -> {
      event.setResult(KickedFromServerEvent.RedirectPlayer.create(mock(RegisteredServer.class)));
      return false;
    });
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertFalse(this.login.getOnKickCallback().apply(this.kick(false)));
    verify(this.plugin, never()).addPlayer(any(), any());
  }

  @Test
  void doesNotOverridePreviousCallbacksAuthenticationFailure() {
    this.login.setOnKickCallback(event -> {
      event.setResult(KickedFromServerEvent.DisconnectPlayer.create(Component.text("Authentication required")));
      return false;
    });
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertFalse(this.login.getOnKickCallback().apply(this.kick(false)));
    verify(this.plugin, never()).addPlayer(any(), any());
  }

  @Test
  void doesNotInterceptInitialConnectionOrAuthenticationKicks() {
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertFalse(this.login.getOnKickCallback().apply(this.kick(true)));
    verify(this.plugin, never()).addPlayer(any(), any());
  }

  @Test
  void preservesReconnectForMatchingEstablishedServerShutdown() {
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertTrue(this.login.getOnKickCallback().apply(this.kick(false)));
    verify(this.plugin).addPlayer(this.player, this.server);
  }

  @Test
  void acceptsMatchingShutdownOnlyDuringManagedNativeQueueRetry() {
    when(this.plugin.isManagedQueueRetry(this.player, this.server, this.reason)).thenReturn(true);
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertTrue(this.login.getOnKickCallback().apply(this.kick(true)));
    verify(this.plugin).addPlayer(this.player, this.server);
  }

  @Test
  void managedQueueRetryRecoversTransientReasonOutsideInitialRestartRegex() {
    Component full = Component.text("Server is full");
    when(this.plugin.isManagedQueueRetry(this.player, this.server, full)).thenReturn(true);
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);
    KickedFromServerEvent event = new KickedFromServerEvent(this.player, this.server, full, true,
        KickedFromServerEvent.DisconnectPlayer.create(full));

    assertTrue(this.login.getOnKickCallback().apply(event));
    verify(this.plugin).addPlayer(this.player, this.server);
  }

  @Test
  void managedRetryStillPreservesPreviousPluginsAuthenticationFailure() {
    when(this.plugin.isManagedQueueRetry(this.player, this.server, this.reason)).thenReturn(true);
    this.login.setOnKickCallback(event -> {
      event.setResult(KickedFromServerEvent.DisconnectPlayer.create(Component.text("Authentication required")));
      return false;
    });
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);

    assertFalse(this.login.getOnKickCallback().apply(this.kick(true)));
    verify(this.plugin, never()).addPlayer(any(), any());
  }

  @Test
  void acceptsVelocityCtdWrappedDefaultDisconnectReason() {
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);
    KickedFromServerEvent event = this.kick(false);
    event.setResult(KickedFromServerEvent.DisconnectPlayer.create(Component.translatable("velocity.error.moved-to-new-server")));

    assertTrue(this.login.getOnKickCallback().apply(event));
    verify(this.plugin).addPlayer(this.player, this.server);
  }

  @Test
  void acceptsVelocityCtdDefaultFallbackResult() {
    new ReconnectListener(this.plugin).onLoginLimboRegister(this.login);
    KickedFromServerEvent event = this.kick(false);
    event.setResult(KickedFromServerEvent.RedirectPlayer.create(mock(RegisteredServer.class)));

    assertTrue(this.login.getOnKickCallback().apply(event));
    verify(this.plugin).addPlayer(this.player, this.server);
  }

  private KickedFromServerEvent kick(boolean duringConnect) {
    return new KickedFromServerEvent(this.player, this.server, this.reason, duringConnect,
        KickedFromServerEvent.DisconnectPlayer.create(this.reason));
  }
}
