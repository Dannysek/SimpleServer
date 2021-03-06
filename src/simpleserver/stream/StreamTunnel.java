/*
 * Copyright (c) 2010 SimpleServer authors (see CONTRIBUTORS)
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */
package simpleserver.stream;

import static simpleserver.lang.Translations.t;
import static simpleserver.util.Util.print;
import static simpleserver.util.Util.println;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInput;
import java.io.DataInputStream;
import java.io.DataOutput;
import java.io.DataOutputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import simpleserver.Authenticator.AuthRequest;
import simpleserver.Color;
import simpleserver.Coordinate;
import simpleserver.Coordinate.Dimension;
import simpleserver.Main;
import simpleserver.Player;
import simpleserver.Server;
import simpleserver.command.PlayerListCommand;
import simpleserver.config.data.Chests.Chest;
import simpleserver.config.xml.Config.BlockPermission;
import simpleserver.message.Message;
import simpleserver.message.MessagePacket;

public class StreamTunnel {
  private static final boolean EXPENSIVE_DEBUG_LOGGING = Boolean.getBoolean("EXPENSIVE_DEBUG_LOGGING");
  private static final int IDLE_TIME = 30000;
  private static final int BUFFER_SIZE = 1024;
  private static final byte BLOCK_DESTROYED_STATUS = 2;
  private static final Pattern MESSAGE_PATTERN = Pattern.compile("^<([^>]+)> (.*)$");
  private static final Pattern COLOR_PATTERN = Pattern.compile("\u00a7[0-9a-z]");
  private static final Pattern JOIN_PATTERN = Pattern.compile("\u00a7.((\\d|\\w|\\u00a7)*) (joined|left) the game.");
  private static final String CONSOLE_CHAT_PATTERN = "\\[Server:.*\\]";
  private static final int MESSAGE_SIZE = 360;
  private static final int MAXIMUM_MESSAGE_SIZE = 119;

  private final boolean isServerTunnel;
  private final String streamType;
  private final Player player;
  private final Server server;
  private final byte[] buffer;
  private final Tunneler tunneler;

  private DataInput in;
  private DataOutput out;
  private InputStream inputStream;
  private OutputStream outputStream;
  private StreamDumper inputDumper;
  private StreamDumper outputDumper;

  private boolean inGame = false;

  private volatile long lastRead;
  private volatile boolean run = true;
  private Byte lastPacket;
  private char commandPrefix;

  public StreamTunnel(InputStream in, OutputStream out, boolean isServerTunnel,
                      Player player) {
    this.isServerTunnel = isServerTunnel;
    if (isServerTunnel) {
      streamType = "ServerStream";
    } else {
      streamType = "PlayerStream";
    }

    this.player = player;
    server = player.getServer();
    commandPrefix = server.options.getBoolean("useSlashes") ? '/' : '!';

    inputStream = in;
    outputStream = out;
    DataInputStream dIn = new DataInputStream(in);
    DataOutputStream dOut = new DataOutputStream(out);
    if (EXPENSIVE_DEBUG_LOGGING) {
      try {
        OutputStream dump = new FileOutputStream(streamType + "Input.debug");
        InputStreamDumper dumper = new InputStreamDumper(dIn, dump);
        inputDumper = dumper;
        this.in = dumper;
      } catch (FileNotFoundException e) {
        System.out.println("Unable to open input debug dump!");
        throw new RuntimeException(e);
      }

      try {
        OutputStream dump = new FileOutputStream(streamType + "Output.debug");
        OutputStreamDumper dumper = new OutputStreamDumper(dOut, dump);
        outputDumper = dumper;
        this.out = dumper;
      } catch (FileNotFoundException e) {
        System.out.println("Unable to open output debug dump!");
        throw new RuntimeException(e);
      }
    } else {
      this.in = dIn;
      this.out = dOut;
    }

    buffer = new byte[BUFFER_SIZE];

    tunneler = new Tunneler();
    tunneler.start();

    lastRead = System.currentTimeMillis();
  }

  public void stop() {
    run = false;
  }

  public boolean isAlive() {
    return tunneler.isAlive();
  }

  public boolean isActive() {
    return System.currentTimeMillis() - lastRead < IDLE_TIME
        || player.isRobot();
  }

  private void handlePacket() throws IOException {
    Byte packetId = in.readByte();
    // System.out.println((isServerTunnel ? "server " : "client ") +
    // String.format("%02x", packetId));
    int x;
    byte y;
    int z;
    byte dimension;
    Coordinate coordinate;
    switch (packetId) {
      case 0x00: // Keep Alive
        write(packetId);
        write(in.readInt()); // random number that is returned from server
        break;
      case 0x01: // Login Request/Response
        write(packetId);
        if (!isServerTunnel) {
          write(in.readInt());
          write(readUTF16());
          copyNBytes(5);
          break;
        }
        player.setEntityId(write(in.readInt()));
        write(readUTF16());

        write(in.readByte());

        dimension = in.readByte();
        if (isServerTunnel) {
          player.setDimension(Dimension.get(dimension));
        }
        write(dimension);
        write(in.readByte());
        write(in.readByte());
        if (isServerTunnel) {
          in.readByte();
          write((byte) server.config.properties.getInt("maxPlayers"));
        } else {
          write(in.readByte());
        }

        break;
      case 0x02: // Handshake
        byte version = in.readByte();
        String name = readUTF16();
        boolean nameSet = false;

        if (name.contains(";")) {
          name = name.substring(0, name.indexOf(";"));
        }
        if (name.equals("Player") || !server.authenticator.isMinecraftUp) {
          AuthRequest req = server.authenticator.getAuthRequest(player.getIPAddress());
          if (req != null) {
            name = req.playerName;
            nameSet = server.authenticator.completeLogin(req, player);
          }
          if (req == null || !nameSet) {
            if (!name.equals("Player")) {
              player.addTMessage(Color.RED, "Login verification failed.");
              player.addTMessage(Color.RED, "You were logged in as guest.");
            }
            name = server.authenticator.getFreeGuestName();
            player.setGuest(true);
            nameSet = player.setName(name);
          }
        } else {
          nameSet = player.setName(name);
          if (nameSet) {
            player.updateRealName(name);
          }
        }

        if (player.isGuest() && !server.authenticator.allowGuestJoin()) {
          player.kick(t("Failed to login: User not authenticated"));
          nameSet = false;
        }

        tunneler.setName(streamType + "-" + player.getName());
        write(packetId);
        write(version);
        write(player.getName());
        write(readUTF16());
        write(in.readInt());

        break;
      case 0x03: // Chat Message
        String message = readUTF16();
        MessagePacket messagePacket = new Message().decodeMessage(message);

        if (messagePacket == null) {
          // we are raw text, handle as such

          if (isServerTunnel && server.config.properties.getBoolean("useMsgFormats")) {
            if (server.config.properties.getBoolean("forwardChat") && server.getMessager().wasForwarded(message)) {
              break;
            }

            Matcher colorMatcher = COLOR_PATTERN.matcher(message);
            String cleanMessage = colorMatcher.replaceAll("");

            Matcher messageMatcher = MESSAGE_PATTERN.matcher(cleanMessage);
            if (messageMatcher.find()) {

            } else if (cleanMessage.matches(CONSOLE_CHAT_PATTERN) && !server.config.properties.getBoolean("chatConsoleToOps")) {
              break;
            }

            if (server.config.properties.getBoolean("msgWrap")) {
              sendMessage(message);
            } else {
              if (message.length() > MAXIMUM_MESSAGE_SIZE) {
                //message = message.substring(0, MAXIMUM_MESSAGE_SIZE);
              }
              write(packetId);
              write(message);
            }
          } else if (!isServerTunnel) {

            if (player.isMuted() && !message.startsWith("/")
                    && !message.startsWith("!")) {
              player.addTMessage(Color.RED, "You are muted! You may not send messages to all players.");
              break;
            }

            if (message.charAt(0) == commandPrefix) {
              message = player.parseCommand(message, false);
              if (message == null) {
                break;
              }
              write(packetId);
              write(message);
              return;
            }

            player.sendMessage(message);
          }
        } else {
          // we have a json object
          if (messagePacket.isJoinedPacket()) {
            String username = messagePacket.getJoinedUsername();

            if (isServerTunnel) {
              if (server.bots.ninja(username)) {
                break;
              }
              if (message.contains("join")) {
                player.addTMessage(Color.YELLOW, "%s joined the game.", username);
              } else {
                player.addTMessage(Color.YELLOW, "%s left the game.", username);
              }
              break;
            }
          } else {
            write(packetId);
            write(message);
          }
        }

        break;

      case 0x04: // Time Update
        write(packetId);
        write(in.readLong());
        long time = in.readLong();
        server.setTime(time);
        write(time);
        break;
      case 0x05: // Player Inventory
        write(packetId);
        write(in.readInt());
        write(in.readShort());
        copyItem();
        break;
      case 0x06: // Spawn Position
        write(packetId);
        copyNBytes(12);
        if (server.options.getBoolean("enableEvents")) {
          server.eventhost.execute(server.eventhost.findEvent("onPlayerConnect"), player, true, null);
        }
        break;
      case 0x07: // Use Entity
        int user = in.readInt();
        int target = in.readInt();
        Player targetPlayer = server.playerList.findPlayer(target);
        if (targetPlayer != null) {
          if (targetPlayer.godModeEnabled()) {
            in.readBoolean();
            break;
          }
        }
        write(packetId);
        write(user);
        write(target);
        copyNBytes(1);
        break;
      case 0x08: // Update Health
        write(packetId);
        player.updateHealth(write(in.readFloat()));
        player.getHealth();
        write(in.readShort());
        write(in.readFloat());
        break;
      case 0x09: // Respawn
        write(packetId);
        if (!isServerTunnel) {
          break;
        }
        player.setDimension(Dimension.get(write(in.readInt())));
        write(in.readByte());
        write(in.readByte());
        write(in.readShort());
        write(readUTF16()); // Added in 1.1 (level type)
        if (server.options.getBoolean("enableEvents") && isServerTunnel) {
          server.eventhost.execute(server.eventhost.findEvent("onPlayerRespawn"), player, true, null);
        }
        break;
      case 0x0a: // Player
        write(packetId);
        copyNBytes(1);
        if (!inGame && !isServerTunnel) {
          player.sendMOTD();

          if (server.config.properties.getBoolean("showListOnConnect")) {
            // display player list if enabled in config
            player.execute(PlayerListCommand.class);
          }

          inGame = true;
        }
        break;
      case 0x0b: // Player Position
        write(packetId);
        copyPlayerLocation();
        copyNBytes(1);
        break;
      case 0x0c: // Player Look
        write(packetId);
        copyPlayerLook();
        copyNBytes(1);
        break;
      case 0x0d: // Player Position & Look
        write(packetId);
        copyPlayerLocation();
        copyPlayerLook();
        copyNBytes(1);
        break;
      case 0x0e: // Player Digging
        if (!isServerTunnel) {
          byte status = in.readByte();
          x = in.readInt();
          y = in.readByte();
          z = in.readInt();
          byte face = in.readByte();

          coordinate = new Coordinate(x, y, z, player);

          if (!player.getGroup().ignoreAreas) {
            BlockPermission perm = server.config.blockPermission(player, coordinate);

            if (!perm.use && status == 0) {
              player.addTMessage(Color.RED, "You can not use this block here!");
              break;
            }
            if (!perm.destroy && status == BLOCK_DESTROYED_STATUS) {
              player.addTMessage(Color.RED, "You can not destroy this block!");
              break;
            }
          }

          boolean locked = server.data.chests.isLocked(coordinate);

          if (!locked || player.ignoresChestLocks() || server.data.chests.canOpen(player, coordinate)) {
            if (locked && status == BLOCK_DESTROYED_STATUS) {
              server.data.chests.releaseLock(coordinate);
              server.data.save();
            }

            write(packetId);
            write(status);
            write(x);
            write(y);
            write(z);
            write(face);

            if (player.instantDestroyEnabled()) {
              packetFinished();
              write(packetId);
              write(BLOCK_DESTROYED_STATUS);
              write(x);
              write(y);
              write(z);
              write(face);
            }

            if (status == BLOCK_DESTROYED_STATUS) {
              player.destroyedBlock();
            }
          }
        } else {
          write(packetId);
          copyNBytes(11);
        }
        break;
      case 0x0f: // Player Block Placement
        x = in.readInt();
        y = in.readByte();
        z = in.readInt();
        coordinate = new Coordinate(x, y, z, player);
        final byte direction = in.readByte();
        final short dropItem = in.readShort();
        byte itemCount = 0;
        short uses = 0;
        byte[] data = null;
        if (dropItem != -1) {
          itemCount = in.readByte();
          uses = in.readShort();
          short dataLength = in.readShort();
          if (dataLength != -1) {
            data = new byte[dataLength];
            in.readFully(data);
          }
        }
        byte blockX = in.readByte();
        byte blockY = in.readByte();
        byte blockZ = in.readByte();

        boolean writePacket = true;
        boolean drop = false;

        BlockPermission perm = server.config.blockPermission(player, coordinate, dropItem);

        if (server.options.getBoolean("enableEvents")) {
          player.checkButtonEvents(new Coordinate(x + (x < 0 ? 1 : 0), y + 1, z + (z < 0 ? 1 : 0)));
        }

        if (isServerTunnel || server.data.chests.isChest(coordinate)) {
          // continue
        } else if (!player.getGroup().ignoreAreas && ((dropItem != -1 && !perm.place) || !perm.use)) {
          if (!perm.use) {
            player.addTMessage(Color.RED, "You can not use this block here!");
          } else {
            player.addTMessage(Color.RED, "You can not place this block here!");
          }

          writePacket = false;
          drop = true;
        } else if (dropItem == 54) {
          int xPosition = x;
          byte yPosition = y;
          int zPosition = z;
          switch (direction) {
            case 0:
              --yPosition;
              break;
            case 1:
              ++yPosition;
              break;
            case 2:
              --zPosition;
              break;
            case 3:
              ++zPosition;
              break;
            case 4:
              --xPosition;
              break;
            case 5:
              ++xPosition;
              break;
          }

          Coordinate targetBlock = new Coordinate(xPosition, yPosition, zPosition, player);

          Chest adjacentChest = server.data.chests.adjacentChest(targetBlock);

          if (adjacentChest != null && !adjacentChest.isOpen() && !adjacentChest.ownedBy(player)) {
            player.addTMessage(Color.RED, "The adjacent chest is locked!");
            writePacket = false;
            drop = true;
          } else {
            player.placingChest(targetBlock);
          }
        }

        if (writePacket) {
          write(packetId);
          write(x);
          write(y);
          write(z);
          write(direction);
          write(dropItem);

          if (dropItem != -1) {
            write(itemCount);
            write(uses);
            if (data != null) {
              write((short) data.length);
              out.write(data);
            } else {
              write((short) -1);
            }

            if (dropItem <= 94 && direction >= 0) {
              player.placedBlock();
            }
          }
          write(blockX);
          write(blockY);
          write(blockZ);

          player.openingChest(coordinate);

        } else if (drop) {
          // Drop the item in hand. This keeps the client state in-sync with the
          // server. This generally prevents empty-hand clicks by the client
          // from placing blocks the server thinks the client has in hand.
          write((byte) 0x0e);
          write((byte) 0x04);
          write(x);
          write(y);
          write(z);
          write(direction);
        }

        break;
      case 0x10: // Holding Change
        write(packetId);
        copyNBytes(2);
        break;
      case 0x11: // Use Bed
        write(packetId);
        copyNBytes(14);
        break;
      case 0x12: // Animation
        write(packetId);
        copyNBytes(5);
        break;
      case 0x13: // Entity Action
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        write(in.readInt());
        break;
      case 0x14: // Named Entity Spawn
        int eid = in.readInt();
        name = readUTF16();
        if (!server.bots.ninja(name)) {
          write(packetId);
          write(eid);
          write(name);
          copyNBytes(16);
          copyUnknownBlob();
        } else {
          skipNBytes(16);
          skipUnknownBlob();
        }
        break;
      case 0x16: // Collect Item
        write(packetId);
        copyNBytes(8);
        break;
      case 0x17: // Add Object/Vehicle
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readByte());
        write(in.readByte());
        int flag = in.readInt();
        write(flag);
        if (flag > 0) {
          write(in.readShort());
          write(in.readShort());
          write(in.readShort());
        }
        break;
      case 0x18: // Mob Spawn
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readByte());
        write(in.readByte());
        write(in.readByte());
        write(in.readShort());
        write(in.readShort());
        write(in.readShort());

        copyUnknownBlob();
        break;
      case 0x19: // Entity: Painting
        write(packetId);
        write(in.readInt());
        write(readUTF16());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        break;
      case 0x1a: // Experience Orb
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readShort());
        break;
      case 0x1b: // Steer Vehicle
        write(packetId);
        write(in.readFloat());
        write(in.readFloat());
        write(in.readBoolean());
        write(in.readBoolean());
        break;
      case 0x1c: // Entity Velocity
        write(packetId);
        copyNBytes(10);
        break;
      case 0x1d: // Destroy Entity
        write(packetId);
        byte destoryCount = write(in.readByte());
        if (destoryCount > 0) {
          copyNBytes(destoryCount * 4);
        }
        break;
      case 0x1e: // Entity
        write(packetId);
        copyNBytes(4);
        break;
      case 0x1f: // Entity Relative Move
        write(packetId);
        copyNBytes(7);
        break;
      case 0x20: // Entity Look
        write(packetId);
        copyNBytes(6);
        break;
      case 0x21: // Entity Look and Relative Move
        write(packetId);
        copyNBytes(9);
        break;
      case 0x22: // Entity Teleport
        write(packetId);
        copyNBytes(18);
        break;
      case 0x23: // Entitiy Look
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        break;
      case 0x26: // Entity Status
        write(packetId);
        copyNBytes(5);
        break;
      case 0x27: // Attach Entity
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readBoolean());
        break;
      case 0x28: // Entity Metadata
        write(packetId);
        write(in.readInt());

        copyUnknownBlob();
        break;
      case 0x29: // Entity Effect
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        write(in.readByte());
        write(in.readShort());
        break;
      case 0x2a: // Remove Entity Effect
        write(packetId);
        write(in.readInt());
        write(in.readByte());
        break;
      case 0x2b: // Experience
        write(packetId);
        player.updateExperience(write(in.readFloat()), write(in.readShort()), write(in.readShort()));
        break;
      case 0x2c: // Entity Properties
        write(packetId);
        write(in.readInt());
        int properties_count = in.readInt();
        short list_length = 0;
        write(properties_count);

        // loop for every property key/value pair
        for (int i = 0; i < properties_count; i++) {
          write(readUTF16());
          write(in.readDouble());

          // grab list elements
          list_length = in.readShort();
          write(list_length);
          if (list_length > 0) {
            for (int k = 0; k < list_length; k++) {
              write(in.readLong());
              write(in.readLong());
              write(in.readDouble());
              write(in.readByte());
            }
          }
        }
        break;
      case 0x33: // Map Chunk
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readBoolean());
        write(in.readShort());
        write(in.readShort());
        copyNBytes(write(in.readInt()));
        break;
      case 0x34: // Multi Block Change
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readShort());
        copyNBytes(write(in.readInt()));
        break;
      case 0x35: // Block Change
        write(packetId);
        x = in.readInt();
        y = in.readByte();
        z = in.readInt();
        short blockType = in.readShort();
        byte metadata = in.readByte();
        coordinate = new Coordinate(x, y, z, player);

        if (blockType == 54 && player.placedChest(coordinate)) {
          lockChest(coordinate);
          player.placingChest(null);
        }

        write(x);
        write(y);
        write(z);
        write(blockType);
        write(metadata);

        break;
      case 0x36: // Block Action
        write(packetId);
        copyNBytes(14);
        break;
      case 0x37: // Mining progress
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readByte());
        break;
      case 0x38: // Chunk Bulk
        write(packetId);
        short chunkCount = in.readShort();
        int dataLength = in.readInt();
        write(chunkCount);
        write(dataLength);
        write(in.readBoolean());
        copyNBytes(chunkCount * 12 + dataLength);
        break;
      case 0x3c: // Explosion
        write(packetId);
        copyNBytes(28);
        int recordCount = in.readInt();
        write(recordCount);
        copyNBytes(recordCount * 3);
        write(in.readFloat());
        write(in.readFloat());
        write(in.readFloat());
        break;
      case 0x3d: // Sound/Particle Effect
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        write(in.readByte());
        write(in.readInt());
        write(in.readInt());
        write(in.readByte());
        break;
      case 0x3e: // Named Sound/Particle Effect
        write(packetId);
        write(readUTF16());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        write(in.readFloat());
        write(in.readByte());
        break;
      case 0x3f: // particle
        write(packetId);
        write(readUTF16()); // name of particle
        write(in.readFloat()); // x
        write(in.readFloat()); // y
        write(in.readFloat()); // z
        write(in.readFloat()); // offset x
        write(in.readFloat()); // offset y
        write(in.readFloat()); // offset z
        write(in.readFloat()); // particle speed
        write(in.readInt()); // num of particles
        break;
      case 0x46: // New/Invalid State
        write(packetId);
        write(in.readByte());
        write(in.readByte());
        break;
      case 0x47: // Thunderbolt
        write(packetId);
        copyNBytes(17);
        break;
      case 0x64: // Open Window
        boolean allow = true;
        byte id = in.readByte();
        byte invtype = in.readByte();
        String title = readUTF16();
        byte number = in.readByte();
        boolean provided = in.readBoolean();
        int unknown = 0;
        if (invtype == 11) {
          unknown = in.readInt();
        }
        if (invtype == 0) {
          Chest adjacent = server.data.chests.adjacentChest(player.openedChest());
          if (!server.data.chests.isChest(player.openedChest())) {
            if (adjacent == null) {
              server.data.chests.addOpenChest(player.openedChest());
            } else {
              server.data.chests.giveLock(adjacent.owner, player.openedChest(), adjacent.name);
            }
            server.data.save();
          }
          if (!player.getGroup().ignoreAreas && (!server.config.blockPermission(player, player.openedChest()).chest || (adjacent != null && !server.config.blockPermission(player, adjacent.coordinate).chest))) {
            player.addTMessage(Color.RED, "You can't use chests here");
            allow = false;
          } else if (server.data.chests.canOpen(player, player.openedChest()) || player.ignoresChestLocks()) {
            if (server.data.chests.isLocked(player.openedChest())) {
              if (player.isAttemptingUnlock()) {
                server.data.chests.unlock(player.openedChest());
                server.data.save();
                player.setAttemptedAction(null);
                player.addTMessage(Color.RED, "This chest is no longer locked!");
                title = t("Open Chest");
              } else {
                title = server.data.chests.chestName(player.openedChest());
              }
            } else {
              title = t("Open Chest");
              if (player.isAttemptLock()) {
                lockChest(player.openedChest());
                title = (player.nextChestName() == null) ? t("Locked Chest") : player.nextChestName();
              }
            }

          } else {
            player.addTMessage(Color.RED, "This chest is locked!");
            allow = false;
          }
        }
        if (!allow) {
          write((byte) 0x65);
          write(id);
        } else {
          write(packetId);
          write(id);
          write(invtype);
          write(title);
          write(number);
          write(provided);
          if (invtype == 11) {
            write(unknown);
          }
        }
        break;
      case 0x65: // Close Window
        write(packetId);
        write(in.readByte());
        break;
      case 0x66: // Window Click
        write(packetId);
        write(in.readByte());
        write(in.readShort());
        write(in.readByte());
        write(in.readShort());
        write(in.readByte());
        copyItem();
        break;
      case 0x67: // Set Slot
        write(packetId);
        write(in.readByte());
        write(in.readShort());
        copyItem();
        break;
      case 0x68: // Window Items
        write(packetId);
        write(in.readByte());
        short count = write(in.readShort());
        for (int c = 0; c < count; ++c) {
          copyItem();
        }
        break;
      case 0x69: // Update Window Property
        write(packetId);
        write(in.readByte());
        write(in.readShort());
        write(in.readShort());
        break;
      case 0x6a: // Transaction
        write(packetId);
        write(in.readByte());
        write(in.readShort());
        write(in.readByte());
        break;
      case 0x6b: // Creative Inventory Action
        write(packetId);
        write(in.readShort());
        copyItem();
        break;
      case 0x6c: // Enchant Item
        write(packetId);
        write(in.readByte());
        write(in.readByte());
        break;
      case (byte) 0x82: // Update Sign
        write(packetId);
        write(in.readInt());
        write(in.readShort());
        write(in.readInt());
        write(readUTF16());
        write(readUTF16());
        write(readUTF16());
        write(readUTF16());
        break;
      case (byte) 0x83: // Item Data
        write(packetId);
        write(in.readShort());
        write(in.readShort());
        short length = in.readShort();
        write(length);
        copyNBytes(length);
        break;
      case (byte) 0x84: // added in 12w06a
        write(packetId);
        write(in.readInt());
        write(in.readShort());
        write(in.readInt());
        write(in.readByte());
        short nbtLength = write(in.readShort());
        if (nbtLength > 0) {
          copyNBytes(nbtLength);
        }
        break;
      case (byte) 0x85: // Unknown (Signs?)
        write(packetId);
        write(in.readByte());
        write(in.readInt());
        write(in.readInt());
        write(in.readInt());
        break;
      case (byte) 0xc3: // BukkitContrib
        write(packetId);
        write(in.readInt());
        copyNBytes(write(in.readInt()));
        break;
      case (byte) 0xc8: // Increment Statistic
        write(packetId);
        write(in.readInt());
        write(in.readInt());
        break;
      case (byte) 0xc9: // Player List Item
        write(packetId);
        write(readUTF16());
        write(in.readByte());
        write(in.readShort());
        break;
      case (byte) 0xca: // Player Abilities
        write(packetId);
        byte flags = in.readByte();
        int creative = flags & 1;
        int flying = flags & 2;
        int can_fly = flags & 4;
        int god_mode = flags & 8;

        write(flags);
        write(in.readFloat());
        write(in.readFloat());
        break;
      case (byte) 0xcb: // Tab-Completion
        write(packetId);
        write(readUTF16());
        break;
      case (byte) 0xcc: // Locale and View Distance
        write(packetId);
        write(readUTF16());
        write(in.readByte());
        write(in.readByte());
        write(in.readByte());
        write(in.readBoolean());
        break;
      case (byte) 0xcd: // Login & Respawn
        write(packetId);
        write(in.readByte());
        break;
      case (byte) 0xce: // scoreboard objectives
        write(packetId);
        write(readUTF16());
        write(readUTF16());
        write(in.readByte());
        break;
      case (byte) 0xcf: // update score
        write(packetId);
        write(readUTF16());
        byte updateRemove = in.readByte();
        write(updateRemove);
        if (updateRemove != 1) {
          write(readUTF16());
          write(in.readInt());
        }
        break;
      case (byte) 0xd0: // display scoreboard
        write(packetId);
        write(in.readByte());
        write(readUTF16());
        break;
      case (byte) 0xd1: // teams
        write(packetId);
        write(readUTF16());
        byte mode = in.readByte();
        short playerCount = -1;
        write(mode);

        if (mode == 0 || mode == 2) {
          write(readUTF16()); // team display name
          write(readUTF16()); // team prefix
          write(readUTF16()); // team suffix
          write(in.readByte()); // friendly fire
        }

        // only ran if 0,3,4
        if (mode == 0 || mode == 3 || mode == 4) {
          playerCount = in.readShort();
          write(playerCount);

          if (playerCount != -1) {
            for (int i = 0; i < playerCount; i++) {
              write(readUTF16());
            }
          }
        }
        break;
      case (byte) 0xd3: // Red Power (mod by Eloraam)
        write(packetId);
        copyNBytes(1);
        copyVLC();
        copyVLC();
        copyVLC();
        copyNBytes((int) copyVLC());
        break;
      case (byte) 0xe6: // ModLoaderMP by SDK
        write(packetId);
        write(in.readInt()); // mod
        write(in.readInt()); // packet id
        copyNBytes(write(in.readInt()) * 4); // ints
        copyNBytes(write(in.readInt()) * 4); // floats
        copyNBytes(write(in.readInt()) * 8); // doubles
        int sizeString = write(in.readInt()); // strings
        for (int i = 0; i < sizeString; i++) {
          copyNBytes(write(in.readInt()));
        }
        break;
      case (byte) 0xfa: // Plugin Message
        write(packetId);
        write(readUTF16());
        copyNBytes(write(in.readShort()));
        break;
      case (byte) 0xfc: // Encryption Key Response
        byte[] sharedKey = new byte[in.readShort()];
        in.readFully(sharedKey);
        byte[] challengeTokenResponse = new byte[in.readShort()];
        in.readFully(challengeTokenResponse);
        if (!isServerTunnel) {
          if (!player.clientEncryption.checkChallengeToken(challengeTokenResponse)) {
            player.kick("Invalid client response");
            break;
          }
          player.clientEncryption.setEncryptedSharedKey(sharedKey);
          sharedKey = player.serverEncryption.getEncryptedSharedKey();
        }
        if (!isServerTunnel && server.authenticator.useCustAuth(player)
            && !server.authenticator.onlineAuthenticate(player)) {
          player.kick(t("%s Failed to login: User not premium", "[CustAuth]"));
          break;
        }
        write(packetId);
        write((short) sharedKey.length);
        write(sharedKey);
        challengeTokenResponse = player.serverEncryption.encryptChallengeToken();
        write((short) challengeTokenResponse.length);
        write(challengeTokenResponse);
        if (isServerTunnel) {
          in = new DataInputStream(new BufferedInputStream(player.serverEncryption.encryptedInputStream(inputStream)));
          out = new DataOutputStream(new BufferedOutputStream(player.clientEncryption.encryptedOutputStream(outputStream)));
        } else {
          in = new DataInputStream(new BufferedInputStream(player.clientEncryption.encryptedInputStream(inputStream)));
          out = new DataOutputStream(new BufferedOutputStream(player.serverEncryption.encryptedOutputStream(outputStream)));
        }
        break;
      case (byte) 0xfd: // Encryption Key Request (server -> client)
        tunneler.setName(streamType + "-" + player.getName());
        write(packetId);
        String serverId = readUTF16();
        if (!server.authenticator.useCustAuth(player)) {
          serverId = "-";
        } else {
          serverId = player.getConnectionHash();
        }
        write(serverId);
        byte[] keyBytes = new byte[in.readShort()];
        in.readFully(keyBytes);
        byte[] challengeToken = new byte[in.readShort()];
        in.readFully(challengeToken);
        player.serverEncryption.setPublicKey(keyBytes);
        byte[] key = player.clientEncryption.getPublicKey();
        write((short) key.length);
        write(key);
        write((short) challengeToken.length);
        write(challengeToken);
        player.serverEncryption.setChallengeToken(challengeToken);
        player.clientEncryption.setChallengeToken(challengeToken);
        break;
      case (byte) 0xfe: // Server List Ping
        write(packetId);
        write(in.readByte());
        break;
      case (byte) 0xff: // Disconnect/Kick
        write(packetId);
        String reason = readUTF16();
        if (reason.startsWith("\u00a71")) {
          reason = String.format("\u00a71\0%s\0%s\0%s\0%s\0%s",
                                 Main.protocolVersion,
                                 Main.minecraftVersion,
                                 server.config.properties.get("serverDescription"),
                                 server.playerList.size(),
                                 server.config.properties.getInt("maxPlayers"));
        }
        write(reason);
        if (reason.startsWith("Took too long")) {
          server.addRobot(player);
        }
        player.close();
        break;
      default:
        if (EXPENSIVE_DEBUG_LOGGING) {
          while (true) {
            skipNBytes(1);
            flushAll();
          }
        } else {
          if (lastPacket != null) {
            throw new IOException("Unable to parse unknown " + streamType
                + " packet 0x" + Integer.toHexString(packetId) + " for player "
                + player.getName() + " (after 0x" + Integer.toHexString(lastPacket));
          } else {
            throw new IOException("Unable to parse unknown " + streamType
                + " packet 0x" + Integer.toHexString(packetId) + " for player "
                + player.getName());
          }
        }
    }
    packetFinished();
    lastPacket = (packetId == 0x00) ? lastPacket : packetId;
  }

  private void copyItem() throws IOException {
    if (write(in.readShort()) > 0) {
      write(in.readByte());
      write(in.readShort());
      short length;
      if ((length = write(in.readShort())) > 0) {
        copyNBytes(length);
      }
    }
  }

  private void skipItem() throws IOException {
    if (in.readShort() > 0) {
      in.readByte();
      in.readShort();
      short length;
      if ((length = in.readShort()) > 0) {
        skipNBytes(length);
      }
    }
  }

  private long copyVLC() throws IOException {
    long value = 0;
    int shift = 0;
    while (true) {
      int i = write(in.readByte());
      value |= (i & 0x7F) << shift;
      if ((i & 0x80) == 0) {
        break;
      }
      shift += 7;
    }
    return value;
  }

  private String readUTF16() throws IOException {
    short length = in.readShort();
    StringBuilder string = new StringBuilder();
    for (int i = 0; i < length; i++) {
      string.append(in.readChar());
    }
    return string.toString();
  }

  private void lockChest(Coordinate coordinate) {
    Chest adjacentChest = server.data.chests.adjacentChest(coordinate);
    if (player.isAttemptLock() || adjacentChest != null && !adjacentChest.isOpen()) {
      if (adjacentChest != null && !adjacentChest.isOpen()) {
        server.data.chests.giveLock(adjacentChest.owner, coordinate, adjacentChest.name);
      } else {
        if (adjacentChest != null) {
          adjacentChest.lock(player);
          adjacentChest.name = player.nextChestName();
        }
        server.data.chests.giveLock(player, coordinate, player.nextChestName());
      }
      player.setAttemptedAction(null);
      player.addTMessage(Color.GRAY, "This chest is now locked.");
    } else if (!server.data.chests.isChest(coordinate)) {
      server.data.chests.addOpenChest(coordinate);
    }
    server.data.save();
  }

  private void copyPlayerLocation() throws IOException {
    double x = in.readDouble();
    double y = in.readDouble();
    double stance = in.readDouble();
    double z = in.readDouble();
    player.position.updatePosition(x, y, z, stance);
    if (server.options.getBoolean("enableEvents")) {
      player.checkLocationEvents();
    }
    write(x);
    write(y);
    write(stance);
    write(z);
  }

  private void copyPlayerLook() throws IOException {
    float yaw = in.readFloat();
    float pitch = in.readFloat();
    player.position.updateLook(yaw, pitch);
    write(yaw);
    write(pitch);
  }

  private void copyUnknownBlob() throws IOException {
    byte item = in.readByte();
    write(item);

    while (item != 0x7f) {
      int type = (item & 0xE0) >> 5;

      switch (type) {
        case 0:
          write(in.readByte());
          break;
        case 1:
          write(in.readShort());
          break;
        case 2:
          write(in.readInt());
          break;
        case 3:
          write(in.readFloat());
          break;
        case 4:
          write(readUTF16());
          break;
        case 5:
          copyItem();
          break;
        case 6:
          write(in.readInt());
          write(in.readInt());
          write(in.readInt());
      }

      item = in.readByte();
      write(item);
    }
  }

  private void skipUnknownBlob() throws IOException {
    byte item = in.readByte();

    while (item != 0x7f) {
      int type = (item & 0xE0) >> 5;

      switch (type) {
        case 0:
          in.readByte();
          break;
        case 1:
          in.readShort();
          break;
        case 2:
          in.readInt();
          break;
        case 3:
          in.readFloat();
          break;
        case 4:
          readUTF16();
          break;
        case 5:
          skipItem();
          break;
        case 6:
          in.readInt();
          in.readInt();
          in.readInt();
      }

      item = in.readByte();
    }
  }

  private byte write(byte b) throws IOException {
    out.writeByte(b);
    return b;
  }

  private byte[] write(byte[] b) throws IOException {
    out.write(b);
    return b;
  }

  private short write(short s) throws IOException {
    out.writeShort(s);
    return s;
  }

  private int write(int i) throws IOException {
    out.writeInt(i);
    return i;
  }

  private long write(long l) throws IOException {
    out.writeLong(l);
    return l;
  }

  private float write(float f) throws IOException {
    out.writeFloat(f);
    return f;
  }

  private double write(double d) throws IOException {
    out.writeDouble(d);
    return d;
  }

  private String write(String s) throws IOException {
    write((short) s.length());
    out.writeChars(s);
    return s;
  }

  private boolean write(boolean b) throws IOException {
    out.writeBoolean(b);
    return b;
  }

  private void skipNBytes(int bytes) throws IOException {
    int overflow = bytes / buffer.length;
    for (int c = 0; c < overflow; ++c) {
      in.readFully(buffer, 0, buffer.length);
    }
    in.readFully(buffer, 0, bytes % buffer.length);
  }

  private void copyNBytes(int bytes) throws IOException {
    int overflow = bytes / buffer.length;
    for (int c = 0; c < overflow; ++c) {
      in.readFully(buffer, 0, buffer.length);
      out.write(buffer, 0, buffer.length);
    }
    in.readFully(buffer, 0, bytes % buffer.length);
    out.write(buffer, 0, bytes % buffer.length);
  }

  private void kick(String reason) throws IOException {
    write((byte) 0xff);
    write(reason);
    packetFinished();
  }

  private String getLastColorCode(String message) {
    String colorCode = "";
    int lastIndex = message.lastIndexOf('\u00a7');
    if (lastIndex != -1 && lastIndex + 1 < message.length()) {
      colorCode = message.substring(lastIndex, lastIndex + 2);
    }

    return colorCode;
  }

  private void sendMessage(String message) throws IOException {
    if (message.length() > 0) {
      int end = message.length();
      if (message.charAt(end - 1) == '\u00a7') {
        end--;
      }
      sendMessagePacket(message.substring(0, end));
    }
  }

  private void sendMessagePacket(String message) throws IOException {
    if (message.length() > 0) {
      write((byte) 0x03);
      write(message);
      packetFinished();
    }
  }

  private void packetFinished() throws IOException {
    if (EXPENSIVE_DEBUG_LOGGING) {
      inputDumper.packetFinished();
      outputDumper.packetFinished();
    }
  }

  private void flushAll() throws IOException {
    try {
      ((OutputStream) out).flush();
    } finally {
      if (EXPENSIVE_DEBUG_LOGGING) {
        inputDumper.flush();
      }
    }
  }

  private final class Tunneler extends Thread {
    @Override
    public void run() {
      try {
        while (run) {
          lastRead = System.currentTimeMillis();

          try {
            handlePacket();

            if (isServerTunnel) {
              while (player.hasMessages()) {
                sendMessage(player.getMessage());
              }
            } else {
              while (player.hasForwardMessages()) {
                sendMessage(player.getForwardMessage());
              }
            }

            flushAll();
          } catch (IOException e) {
            if (run && !player.isRobot()) {
              println(e);
              print(streamType
                  + " error handling traffic for " + player.getIPAddress());
              if (lastPacket != null) {
                System.out.print(" (" + Integer.toHexString(lastPacket) + ")");
              }
              System.out.println();
            }
            break;
          }
        }

        try {
          if (player.isKicked()) {
            kick(player.getKickMsg());
          }
          flushAll();
        } catch (IOException e) {
        }
      } finally {
        if (EXPENSIVE_DEBUG_LOGGING) {
          inputDumper.cleanup();
          outputDumper.cleanup();
        }
      }
    }
  }
}
