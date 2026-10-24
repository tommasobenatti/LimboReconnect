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

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.elytrium.commons.kyori.serialization.Serializers;
import net.elytrium.limboapi.api.file.BuiltInWorldFileType;
import net.elytrium.limboapi.api.player.GameMode;
import net.elytrium.serializer.SerializerConfig;
import net.elytrium.serializer.annotations.Comment;
import net.elytrium.serializer.annotations.CommentValue;
import net.elytrium.serializer.language.object.YamlSerializable;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public class Config extends YamlSerializable {

  private static final SerializerConfig CONFIG = new SerializerConfig.Builder().setCommentValueIndent(1).build();


  @Comment(value = {
      @CommentValue("Available serializers:"),
      @CommentValue("LEGACY_AMPERSAND - \"&c&lExample &c&9Text\"."),
      @CommentValue("LEGACY_SECTION - \"§c§lExample §c§9Text\"."),
      @CommentValue("MINIMESSAGE - \"<bold><red>Example</red> <blue>Text</blue></bold>\". (https://webui.adventure.kyori.net/)"),
      @CommentValue("GSON - \"[{\"text\":\"Example\",\"bold\":true,\"color\":\"red\"},{\"text\":\" \",\"bold\":true},{\"text\":\"Text\",\"bold\":true,\"color\":\"blue\"}]\". (https://minecraft.tools/en/json_text.php/)"),
      @CommentValue("GSON_COLOR_DOWNSAMPLING - Same as GSON, but uses downsampling.")
  })
  public Serializers serializer = Serializers.MINIMESSAGE;

  @Comment(value = @CommentValue("Send player to the limbo, if disconnect reason contains this text (using regex)"))
  public String triggerMessage
      = "((?i)^(server closed|server is restarting|multiplayer\\.disconnect\\.server_shutdown))+$";

  @Comment(value = @CommentValue("Server status check interval in milliseconds"))
  public long checkInterval = 1000;
  @Comment(value = @CommentValue("Server status check timeout in milliseconds"))
  public long pingTimeout = 500;
  @Comment(value = @CommentValue("Reconnect delay after server startup"))
  public long joinDelay = 2000;
  @Comment(value = @CommentValue("Send to limbo or use current server's world"))
  public boolean useLimbo = false;
  @Comment(value = @CommentValue("Require permission to reconnect (limboreconnect.reconnect)"))
  public boolean requirePermission = false;
  @Comment(value = @CommentValue("Use Velocity-CTD's native queue for reconnects, preserving its priorities and backend readiness checks"))
  public boolean integrateCtdQueue = true;

  public World world;
  public Messages messages;
  public Sounds sounds;
  public boolean debug = false;


  public Config() {
    super(Config.CONFIG);
    this.world = new World();
    this.messages = new Messages();
    this.sounds = new Sounds();
  }

  boolean loadCandidate(Path path) {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setMaxAliasesForCollections(0);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    try (BufferedReader reader = Files.newBufferedReader(path)) {
      Object values = yaml.load(reader);
      StringWriter defaults = new StringWriter();
      try (BufferedWriter writer = new BufferedWriter(defaults)) {
        new Config().save(writer);
      }
      Object schema = yaml.load(defaults.toString());
      DumperOptions output = new DumperOptions();
      output.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      String knownSettings = new Yaml(output).dump(knownSettings(values, schema));
      // The typed reader flags unknown administrator keys as parse errors, so validate a known-only view.
      // The original candidate remains untouched and is the only document committed to disk.
      return this.load(new BufferedReader(new StringReader(knownSettings)));
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  private static Object knownSettings(Object values, Object schema) {
    if (schema instanceof Map<?, ?> schemaMap) {
      if (!(values instanceof Map<?, ?> valueMap)) {
        throw new IllegalArgumentException("Expected a configuration section");
      }
      Map<Object, Object> result = new LinkedHashMap<>();
      for (Map.Entry<?, ?> entry : schemaMap.entrySet()) {
        if (valueMap.containsKey(entry.getKey())) {
          result.put(entry.getKey(), knownSettings(valueMap.get(entry.getKey()), entry.getValue()));
        }
      }
      return result;
    }
    if (schema instanceof List<?> schemaList) {
      if (!(values instanceof List<?> valueList)) {
        throw new IllegalArgumentException("Expected a configuration list");
      }
      List<Object> result = new ArrayList<>();
      for (Object value : valueList) {
        result.add(schemaList.isEmpty() ? value : knownSettings(value, schemaList.getFirst()));
      }
      return result;
    }
    if ((schema instanceof Boolean && !(values instanceof Boolean))
        || (schema instanceof String && !(values instanceof String))
        || (schema instanceof Number && (!(values instanceof Number number) || !Double.isFinite(number.doubleValue())))
        || (schema != null && values == null)) {
      throw new IllegalArgumentException("Invalid configuration value type");
    }
    return values;
  }


  public static class World {

    @Comment(value = @CommentValue("Dimensions: OVERWORLD, NETHER, THE_END"))
    public String dimension = "OVERWORLD";
    @Comment(value = @CommentValue("Load world from file"))
    public boolean loadWorld = false;
    @Comment(value = @CommentValue("Schematic file name"))
    public String worldFilePath = "world.schem";
    public BuiltInWorldFileType worldFileType = BuiltInWorldFileType.WORLDEDIT_SCHEM;
    public int worldLightLevel = 15;
    public GameMode gamemode = GameMode.ADVENTURE;


    public WorldCoords worldCoords;
    public PlayerCoords playerCoords;

    public World() {
      this.worldCoords = new WorldCoords();
      this.playerCoords = new PlayerCoords();
    }

    public static class WorldCoords {

      public int x = 0;
      public int y = 100;
      public int z = 0;

      public WorldCoords() {
      }
    }

    public static class PlayerCoords {

      public int x = 0;
      public int y = 100;
      public int z = 0;
      public float pitch = 0;
      public float yaw = 90;

      public PlayerCoords() {
      }
    }
  }

  public static class Messages {

    public String reload = "<green>LimboReconnect reloaded";
    public Titles titles;

    public Messages() {
      this.titles = new Titles();
    }

    public static class Titles {

      @Comment(value = @CommentValue("time in ticks"), at = Comment.At.SAME_LINE)
      public long showDelay = 20;

      public List<Title> titles = List.of(
          new Title("Server is restarting."), new Title("Server is restarting.."), new Title("Server is restarting...")
      );
      public List<Title> connectingTitles = List.of(
          new Title("Connecting."), new Title("Connecting.."), new Title("Connecting...")
      );
    }

    public static class Title {

      public String title;
      public String subtitle = "Please wait...";

      public Title() {
      }

      public Title(String title) {
        this.title = title;
      }
    }
  }

  public static class Sounds {

    public Sound waiting = new Sound("entity.experience_orb.pickup");
    public Sound connecting = new Sound("entity.player.levelup");

    public static class Sound {

      public String name;
      public float volume = 1.0f;
      public float pitch = 1.0f;

      public Sound() {
      }

      public Sound(String name) {
        this.name = name;
      }
    }
  }
}
