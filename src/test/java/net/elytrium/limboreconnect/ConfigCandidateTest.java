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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConfigCandidateTest {

  @TempDir
  Path directory;

  @Test
  void acceptsUnknownSettingsWithoutLoadingOrRemovingThem() throws Exception {
    Path path = this.directory.resolve("config.yml");
    String original = "custom-key: 42\nworld:\n  custom-note: preserved\n  gamemode: ADVENTURE\n";
    Files.writeString(path, original);
    Config config = new Config();

    assertTrue(config.loadCandidate(path));
    assertEquals(original, Files.readString(path));
  }

  @Test
  void rejectsInvalidKnownValueEvenWhenUnknownSettingsExist() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "custom-key: 42\nping-timeout: broken\n");

    assertFalse(new Config().loadCandidate(path));
  }

  @Test
  void rejectsStringInBooleanField() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "use-limbo: not-a-boolean\n");

    assertFalse(new Config().loadCandidate(path));
  }

  @Test
  void preservesExplicitlyEmptyTitleLists() throws Exception {
    Path path = this.directory.resolve("config.yml");
    Files.writeString(path, "messages:\n  titles:\n    titles: []\n    connecting-titles: []\n");
    Config config = new Config();

    assertTrue(config.loadCandidate(path));
    assertTrue(config.messages.titles.titles.isEmpty());
    assertTrue(config.messages.titles.connectingTitles.isEmpty());
  }
}
