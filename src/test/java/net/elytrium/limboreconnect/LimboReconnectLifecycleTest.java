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

package net.elytrium.limboreconnect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;

class LimboReconnectLifecycleTest {

  @TempDir
  Path directory;

  @Test
  void reloadDisposesReplacedLimbo() {
    ProxyServer proxy = mock(ProxyServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    PluginContainer container = mock(PluginContainer.class);
    LimboFactory factory = mock(LimboFactory.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    doReturn(Optional.of(factory)).when(container).getInstance();
    when(proxy.getPluginManager().getPlugin("limboapi")).thenReturn(Optional.of(container));
    Limbo first = mock(Limbo.class, org.mockito.Mockito.RETURNS_SELF);
    Limbo second = mock(Limbo.class, org.mockito.Mockito.RETURNS_SELF);
    when(factory.createLimbo(any())).thenReturn(first, second);
    LimboReconnect plugin = new LimboReconnect(mock(Logger.class), proxy, this.directory);

    plugin.reload();
    plugin.reload();

    verify(first).dispose();
  }

  @Test
  void invalidConfigIsRejectedWithoutRewritingIt() throws IOException {
    ProxyServer proxy = mock(ProxyServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    PluginContainer container = mock(PluginContainer.class);
    doReturn(Optional.of(mock(LimboFactory.class, org.mockito.Mockito.RETURNS_DEEP_STUBS))).when(container).getInstance();
    when(proxy.getPluginManager().getPlugin("limboapi")).thenReturn(Optional.of(container));
    LimboReconnect plugin = new LimboReconnect(mock(Logger.class), proxy, this.directory);
    Path config = this.directory.resolve("config.yml");
    String invalid = "serializer: [unterminated\n";
    Files.writeString(config, invalid);

    assertThrows(RuntimeException.class, plugin::reload);
    assertEquals(invalid, Files.readString(config));
  }

  @Test
  void reloadAddsMissingDefaultsAndPreservesUnknownKeysAndComments() throws IOException {
    ProxyServer proxy = mock(ProxyServer.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    PluginContainer container = mock(PluginContainer.class);
    LimboFactory factory = mock(LimboFactory.class, org.mockito.Mockito.RETURNS_DEEP_STUBS);
    doReturn(Optional.of(factory)).when(container).getInstance();
    when(proxy.getPluginManager().getPlugin("limboapi")).thenReturn(Optional.of(container));
    when(factory.createLimbo(any())).thenReturn(mock(Limbo.class, org.mockito.Mockito.RETURNS_SELF));
    LimboReconnect plugin = new LimboReconnect(mock(Logger.class), proxy, this.directory);
    Path config = this.directory.resolve("config.yml");
    Files.writeString(config, "# Administrator comment\ncustom-key: 42\ntrigger-message: 'Server is restarting'\n");

    plugin.reload();
    String updated = Files.readString(config);
    assertTrue(updated.contains("# Administrator comment"));
    assertTrue(updated.contains("custom-key: 42"));
    assertTrue(updated.contains("ping-timeout:"));
    assertTrue(updated.contains("integrate-ctd-queue: true"));
    plugin.reload();
    assertEquals(updated, Files.readString(config));
  }
}
