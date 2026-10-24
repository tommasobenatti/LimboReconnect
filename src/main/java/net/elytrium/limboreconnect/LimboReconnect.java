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

import com.google.inject.Inject;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.connection.DisconnectEvent;
import com.velocitypowered.api.event.player.ServerPostConnectEvent;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.api.plugin.Dependency;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.proxy.connection.MinecraftConnection;
import com.velocitypowered.proxy.connection.MinecraftSessionHandler;
import com.velocitypowered.proxy.connection.client.ClientPlaySessionHandler;
import com.velocitypowered.proxy.connection.client.ConnectedPlayer;
import com.velocitypowered.proxy.protocol.packet.BossBarPacket;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;
import net.elytrium.limboapi.api.Limbo;
import net.elytrium.limboapi.api.LimboFactory;
import net.elytrium.limboapi.api.chunk.Dimension;
import net.elytrium.limboapi.api.chunk.VirtualWorld;
import net.elytrium.limboapi.api.file.WorldFile;
import net.elytrium.limboapi.api.protocol.PacketDirection;
import net.elytrium.limboapi.api.protocol.packets.PacketMapping;
import net.elytrium.limboreconnect.commands.LimboReconnectCommand;
import net.elytrium.limboreconnect.handler.ReconnectHandler;
import net.elytrium.limboreconnect.listener.ReconnectListener;
import net.elytrium.limboreconnect.protocol.packets.PlaySound;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.ComponentSerializer;
import net.kyori.adventure.title.Title;
import org.slf4j.Logger;

@Plugin(
    id = "limboreconnect",
    name = "LimboReconnect",
    version = BuildConstants.VERSION,
    authors = {"SkyWatcher_2019", "hevav"},
    dependencies = {@Dependency(id = "limboapi")}
)
public class LimboReconnect {

  public static volatile Config CONFIG = new Config();
  @Inject
  private static Logger LOGGER;
  private static volatile ComponentSerializer<Component, Component, String> SERIALIZER;
  public volatile List<Title> offlineTitles = new ArrayList<>();
  public volatile List<Title> connectingTitles = new ArrayList<>();
  public volatile Pattern triggerMessage;
  private final ProxyServer server;
  private final Path configPath;
  private final Path dataDirectory;
  private final LimboFactory factory;
  private final CommandMeta commandMeta;
  private Limbo limbo;
  private ReconnectListener listener;
  private final Map<UUID, ReconnectHandler> reconnectHandlers = new ConcurrentHashMap<>();

  @Inject
  public LimboReconnect(Logger logger, ProxyServer server, @DataDirectory Path dataDirectory) {
    setLogger(logger);
    this.server = server;
    this.commandMeta = server.getCommandManager().metaBuilder("limboreconnect").plugin(this).build();

    this.dataDirectory = dataDirectory;
    this.configPath = dataDirectory.resolve("config.yml");

    this.factory = (LimboFactory) this.server.getPluginManager().getPlugin("limboapi").flatMap(PluginContainer::getInstance).orElseThrow();
  }

  public static ComponentSerializer<Component, Component, String> getSerializer() {
    return SERIALIZER;
  }

  private static void setSerializer(ComponentSerializer<Component, Component, String> serializer) {
    SERIALIZER = serializer;
  }

  private static void setConfig(Config config) {
    CONFIG = config;
  }

  public static Logger getLogger() {
    return LOGGER;
  }

  private static void setLogger(Logger logger) {
    LOGGER = logger;
  }

  @Subscribe
  public void onProxyInitialization(ProxyInitializeEvent event) {
    this.reload();
  }

  public synchronized void reload() {
    Config config = new Config();
    try {
      SafeConfigUpdater.update(this.configPath, path -> new Config().save(path),
          path -> config.loadCandidate(path) && validateConfig(config));
    } catch (IOException | RuntimeException exception) {
      throw new IllegalStateException("Unable to safely load config.yml; the previous configuration remains active", exception);
    }

    if (config.triggerMessage.equals("((?i)^(server closed|server is restarting|multiplayer\\.disconnect\\.server_shutdown))+$")) {
      LOGGER.warn("Looks like you using default config!");
      LOGGER.warn("Please check 'trigger-message' option and confirm, that it matches your server's restart message");
    }

    ComponentSerializer<Component, Component, String> serializer = config.serializer.getSerializer();

    Config.World.PlayerCoords playerCoords = config.world.playerCoords;

    VirtualWorld world = this.factory.createVirtualWorld(
        Dimension.valueOf(config.world.dimension), playerCoords.x, playerCoords.y, playerCoords.z, playerCoords.yaw, playerCoords.pitch);

    if (config.world.loadWorld) {
      try {
        Path path = this.dataDirectory.resolve(config.world.worldFilePath);
        WorldFile file = this.factory.openWorldFile(config.world.worldFileType, path);

        Config.World.WorldCoords coords = config.world.worldCoords;
        file.toWorld(this.factory, world, coords.x, coords.y, coords.z, config.world.worldLightLevel);
      } catch (IOException e) {
        throw new IllegalArgumentException(e);
      }
    }

    List<Title> newOfflineTitles = new ArrayList<>();
    config.messages.titles.titles.forEach(title -> newOfflineTitles.add(Title.title(
        serializer.deserialize(title.title),
        serializer.deserialize(title.subtitle),
        Title.Times.times(
            Duration.ofMillis(0),
            Duration.ofMillis(1000 * 30),
            Duration.ofMillis(0)
        )
    )));
    List<Title> newConnectingTitles = new ArrayList<>();
    config.messages.titles.connectingTitles.forEach(title -> newConnectingTitles.add(Title.title(
        serializer.deserialize(title.title),
        serializer.deserialize(title.subtitle),
        Title.Times.times(
            Duration.ofMillis(0),
            Duration.ofMillis(1000 * 30),
            Duration.ofMillis(0)
        )
    )));


    final Limbo newLimbo = this.factory.createLimbo(world)
        .setName("LimboReconnect")
        .setShouldRejoin(config.useLimbo)
        .setShouldRespawn(config.useLimbo)
        .setGameMode(config.world.gamemode)
        .registerPacket(PacketDirection.CLIENTBOUND, PlaySound.class, null, new PacketMapping[] {
            new PacketMapping(0x29, ProtocolVersion.MINECRAFT_1_7_2, true),
            new PacketMapping(0x19, ProtocolVersion.MINECRAFT_1_9, true),
            new PacketMapping(0x1a, ProtocolVersion.MINECRAFT_1_13, true),
            new PacketMapping(0x19, ProtocolVersion.MINECRAFT_1_14, true),
            new PacketMapping(0x1a, ProtocolVersion.MINECRAFT_1_15, true),
            new PacketMapping(0x19, ProtocolVersion.MINECRAFT_1_16, true),
            new PacketMapping(0x18, ProtocolVersion.MINECRAFT_1_16_2, true),
            new PacketMapping(0x19, ProtocolVersion.MINECRAFT_1_17, true),
            new PacketMapping(0x16, ProtocolVersion.MINECRAFT_1_19, true),
            new PacketMapping(0x17, ProtocolVersion.MINECRAFT_1_19_1, true),
            new PacketMapping(0x5e, ProtocolVersion.MINECRAFT_1_19_3, true),
            new PacketMapping(0x62, ProtocolVersion.MINECRAFT_1_19_4, true),
            new PacketMapping(0x64, ProtocolVersion.MINECRAFT_1_20_2, true),
            new PacketMapping(0x66, ProtocolVersion.MINECRAFT_1_20_3, true),
            new PacketMapping(0x68, ProtocolVersion.MINECRAFT_1_20_5, true),
            new PacketMapping(0x6f, ProtocolVersion.MINECRAFT_1_21_2, true),
            new PacketMapping(0x6e, ProtocolVersion.MINECRAFT_1_21_5, true),
            new PacketMapping(0x73, ProtocolVersion.MINECRAFT_1_21_9, true),
            new PacketMapping(0x75, ProtocolVersion.MINECRAFT_26_1, true),
            new PacketMapping(0x77, ProtocolVersion.MINECRAFT_26_3, true),
            });


    final Limbo previousLimbo = this.limbo;
    setConfig(config);
    setSerializer(serializer);
    this.triggerMessage = Pattern.compile(config.triggerMessage);
    this.offlineTitles = List.copyOf(newOfflineTitles);
    this.connectingTitles = List.copyOf(newConnectingTitles);
    this.limbo = newLimbo;
    if (previousLimbo != null) {
      previousLimbo.dispose();
    }

    if (this.listener != null) {
      this.server.getEventManager().unregisterListener(this, this.listener);
    }
    this.listener = new ReconnectListener(this);
    this.server.getEventManager().register(this, this.listener);
    CommandManager commandManager = this.server.getCommandManager();
    commandManager.unregister("limboreconnect");
    commandManager.register(this.commandMeta, new LimboReconnectCommand(this));
  }

  @Subscribe
  public synchronized void onProxyShutdown(ProxyShutdownEvent event) {
    this.reconnectHandlers.values().forEach(ReconnectHandler::close);
    this.reconnectHandlers.clear();
    if (this.limbo != null) {
      this.limbo.dispose();
    }
  }

  @Subscribe
  public void onPlayerDisconnect(DisconnectEvent event) {
    this.releaseHandler(event.getPlayer());
  }

  @Subscribe
  public void onServerPostConnect(ServerPostConnectEvent event) {
    this.releaseHandler(event.getPlayer());
  }

  private void releaseHandler(Player player) {
    ReconnectHandler handler = this.reconnectHandlers.remove(player.getUniqueId());
    if (handler != null) {
      handler.close();
    }
  }

  public ProxyServer getServer() {
    return this.server;
  }

  public void trackHandler(Player player, ReconnectHandler handler) {
    ReconnectHandler previous = this.reconnectHandlers.put(player.getUniqueId(), handler);
    if (previous != null && previous != handler) {
      previous.close();
    }
  }

  public void removeHandler(Player player, ReconnectHandler handler) {
    this.reconnectHandlers.remove(player.getUniqueId(), handler);
  }

  public boolean isManagedQueueRetry(Player player, RegisteredServer target, Component reason) {
    ReconnectHandler handler = this.reconnectHandlers.get(player.getUniqueId());
    return handler != null && handler.canRecoverQueueKick(target, reason);
  }

  public synchronized void recoverPlayer(Player player, ReconnectHandler handler) {
    if (player.isActive() && this.reconnectHandlers.get(player.getUniqueId()) == handler) {
      this.limbo.spawnPlayer(player, handler);
    }
  }

  private static boolean validateConfig(Config config) {
    try {
      if (config.serializer == null || config.world == null || config.messages == null || config.sounds == null
          || config.world.playerCoords == null || config.world.worldCoords == null || config.world.gamemode == null
          || config.messages.titles == null || config.messages.titles.titles == null || config.messages.titles.connectingTitles == null
          || config.sounds.waiting == null || config.sounds.connecting == null || config.sounds.waiting.name == null
          || config.sounds.connecting.name == null || config.checkInterval <= 0 || config.pingTimeout <= 0 || config.joinDelay < 0
          || config.messages.titles.showDelay <= 0 || config.messages.titles.showDelay > Long.MAX_VALUE / 50) {
        return false;
      }
      Pattern.compile(config.triggerMessage);
      Dimension.valueOf(config.world.dimension);
      ComponentSerializer<Component, Component, String> serializer = config.serializer.getSerializer();
      for (Config.Messages.Title title : config.messages.titles.titles) {
        serializer.deserialize(title.title);
        serializer.deserialize(title.subtitle);
      }
      for (Config.Messages.Title title : config.messages.titles.connectingTitles) {
        serializer.deserialize(title.title);
        serializer.deserialize(title.subtitle);
      }
      return true;
    } catch (RuntimeException exception) {
      return false;
    }
  }

  public synchronized void addPlayer(Player player, RegisteredServer server) {
    ConnectedPlayer connectedPlayer = (ConnectedPlayer) player;
    MinecraftConnection connection = connectedPlayer.getConnection();
    MinecraftSessionHandler minecraftSessionHandler = connection.getActiveSessionHandler();
    if (minecraftSessionHandler != null) {
      if (minecraftSessionHandler instanceof ClientPlaySessionHandler sessionHandler) {
        for (UUID bossBar : sessionHandler.getServerBossBars()) {
          BossBarPacket deletePacket = new BossBarPacket();
          deletePacket.setUuid(bossBar);
          deletePacket.setAction(BossBarPacket.REMOVE);
          connectedPlayer.getConnection().delayedWrite(deletePacket);
        }
        sessionHandler.getServerBossBars().clear();
      }
    }

    connectedPlayer.getTabList().clearAll();
    ReconnectHandler handler = this.reconnectHandlers.get(player.getUniqueId());
    if (handler == null || !handler.isNativeQueueTarget(server)) {
      handler = new ReconnectHandler(this, server);
    }
    this.limbo.spawnPlayer(player, handler);
  }
}
