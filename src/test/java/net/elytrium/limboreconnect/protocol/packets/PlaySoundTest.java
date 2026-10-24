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

package net.elytrium.limboreconnect.protocol.packets;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.velocitypowered.api.network.ProtocolVersion;
import com.velocitypowered.proxy.protocol.ProtocolUtils;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.junit.jupiter.api.Test;

class PlaySoundTest {

  @Test
  void modernPacketWritesInlineSoundAndSeedWithoutTrailingData() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      new PlaySound("entity.player.levelup", -0.1, -0.2, -0.3, 1.0F, 1.0F)
          .encode(buffer, ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_19_3);
      assertEquals(0, ProtocolUtils.readVarInt(buffer));
      assertEquals("entity.player.levelup", ProtocolUtils.readString(buffer));
      buffer.readBoolean();
      assertEquals(0, ProtocolUtils.readVarInt(buffer));
      assertEquals(0, buffer.readInt());
      assertEquals(-1, buffer.readInt());
      assertEquals(-2, buffer.readInt());
      assertEquals(1.0F, buffer.readFloat());
      assertEquals(1.0F, buffer.readFloat());
      assertEquals(0L, buffer.readLong());
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }

  @Test
  void legacyHighPitchIsClampedBeforeWritingUnsignedByte() {
    ByteBuf buffer = Unpooled.buffer();
    try {
      new PlaySound("random.levelup", 0, 0, 0, 1.0F, 8.0F)
          .encode(buffer, ProtocolUtils.Direction.CLIENTBOUND, ProtocolVersion.MINECRAFT_1_7_2);
      ProtocolUtils.readString(buffer);
      buffer.skipBytes(16);
      assertEquals(255, buffer.readUnsignedByte());
      assertEquals(0, buffer.readableBytes());
    } finally {
      buffer.release();
    }
  }
}
