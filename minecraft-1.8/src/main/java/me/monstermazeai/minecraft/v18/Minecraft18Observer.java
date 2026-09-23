package me.monstermazeai.minecraft.v18;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.EntityLivingBase;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.tileentity.TileEntityBeacon;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Read-only telemetry recorder for the Minecraft 1.8.9 Monster Maze client.
 *
 * The important design rule is that this class records raw client observations
 * separately from any Monster Maze interpretation.  The resulting JSONL file
 * is intended to let the common Java 17 simulator/adapter be built from real
 * observations without repeatedly asking the player to install another probe.
 */
public final class Minecraft18Observer {
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final Gson GSON = new Gson();

    private static final int MAP_RADIUS = 49;
    private static final int ENTITY_RADIUS = 96;
    private static final int MAX_ENTITIES = 512;
    private static final int MAX_CHAT_LINES = 64;

    private int tickCounter;
    private long sequence;
    private BufferedWriter writer;
    private File logFile;
    private final List<String> chatBuffer = new ArrayList<String>();

    public void tick() {
        if (MC.theWorld == null || MC.thePlayer == null) {
            closeLog();
            tickCounter = 0;
            return;
        }

        tickCounter++;

        if (writer == null) {
            openLog();
        }

        // Record every client tick while a world is loaded.  This intentionally
        // includes the transition into/out of Monster Maze so we can determine
        // exactly which signals are reliable for detection.
        Observation observation = observe();

        JsonObject root = observation.toJson();
        root.addProperty("sequence", sequence++);
        root.addProperty("clientTick", tickCounter);
        root.addProperty("recordedAtMs", System.currentTimeMillis());

        // A full 99x99 floor/block-state map is expensive but highly valuable.
        // Capture it once per second, not on every tick.
        if (tickCounter % 20 == 0) {
            root.add("map99x99", captureMap99x99(MC.theWorld, MC.thePlayer));
        }

        JsonArray chats = new JsonArray();
        synchronized (chatBuffer) {
            for (String chat : chatBuffer) {
                chats.add(new com.google.gson.JsonPrimitive(chat));
            }
            chatBuffer.clear();
        }
        root.add("chat", chats);

        writeLine(GSON.toJson(root));

        // Also keep the console useful without flooding it.
        if (tickCounter % 20 == 0) {
            System.out.println("[MonsterMazeAI/1.8] " + observation.toLogLine()
                    + " log=" + logFile.getAbsolutePath());
        }
    }

    public void onChat(String text) {
        if (text == null || text.length() == 0) {
            return;
        }

        synchronized (chatBuffer) {
            if (chatBuffer.size() >= MAX_CHAT_LINES) {
                chatBuffer.remove(0);
            }
            chatBuffer.add(text);
        }
    }

    public Observation observe() {
        EntityPlayerSP player = MC.thePlayer;
        World world = MC.theWorld;

        ScoreboardSnapshot scoreboard = readScoreboard(world);
        List<EntitySnapshot> entities = readEntities(world, player);
        List<BeaconSnapshot> beacons = readBeacons(world, player);

        BeaconSnapshot nearestBeacon = null;
        for (BeaconSnapshot beacon : beacons) {
            if (nearestBeacon == null || beacon.distanceSq < nearestBeacon.distanceSq) {
                nearestBeacon = beacon;
            }
        }

        int snowmen = 0;
        for (EntitySnapshot entity : entities) {
            if ("EntitySnowman".equals(entity.type)) {
                snowmen++;
            }
        }

        boolean scoreboardDetected = scoreboard.looksLikeMonsterMaze();
        boolean beaconDetected = !beacons.isEmpty();
        boolean monsterDetected = snowmen > 0;

        return new Observation(
                scoreboardDetected || beaconDetected || monsterDetected,
                player,
                scoreboard,
                entities,
                beacons
        );
    }

    private List<EntitySnapshot> readEntities(World world, EntityPlayerSP player) {
        List<EntitySnapshot> result = new ArrayList<EntitySnapshot>();
        double radiusSq = ENTITY_RADIUS * (double) ENTITY_RADIUS;

        for (Entity entity : world.loadedEntityList) {
            if (entity == null || entity == player) {
                continue;
            }

            double dx = entity.posX - player.posX;
            double dy = entity.posY - player.posY;
            double dz = entity.posZ - player.posZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;

            if (distanceSq > radiusSq) {
                continue;
            }

            String uuid = null;
            try {
                UUID id = entity.getUniqueID();
                uuid = id == null ? null : id.toString();
            } catch (Throwable ignored) {
                // Some modded/custom entities can expose an unusual UUID path.
            }

            float health = -1.0F;
            float maxHealth = -1.0F;
            if (entity instanceof EntityLivingBase) {
                EntityLivingBase living = (EntityLivingBase) entity;
                health = living.getHealth();
                maxHealth = living.getMaxHealth();
            }

            result.add(new EntitySnapshot(
                    entity.getEntityId(),
                    entity.getClass().getName(),
                    entity.getName(),
                    uuid,
                    entity.posX,
                    entity.posY,
                    entity.posZ,
                    entity.motionX,
                    entity.motionY,
                    entity.motionZ,
                    entity.rotationYaw,
                    entity.rotationPitch,
                    entity.onGround,
                    entity.width,
                    entity.height,
                    health,
                    maxHealth,
                    Math.sqrt(distanceSq)
            ));

            if (result.size() >= MAX_ENTITIES) {
                break;
            }
        }

        return result;
    }

    private List<BeaconSnapshot> readBeacons(World world, EntityPlayerSP player) {
        List<BeaconSnapshot> result = new ArrayList<BeaconSnapshot>();

        // This is dramatically cheaper than scanning ~180,000 block positions.
        // loadedTileEntityList is the client-side list of loaded tile entities.
        for (TileEntity tile : world.loadedTileEntityList) {
            if (!(tile instanceof TileEntityBeacon)) {
                continue;
            }

            BlockPos pos = tile.getPos();
            double dx = pos.getX() + 0.5D - player.posX;
            double dy = pos.getY() + 0.5D - player.posY;
            double dz = pos.getZ() + 0.5D - player.posZ;
            double distanceSq = dx * dx + dy * dy + dz * dz;

            if (distanceSq > ENTITY_RADIUS * (double) ENTITY_RADIUS) {
                continue;
            }

            TileEntityBeacon beacon = (TileEntityBeacon) tile;
            result.add(new BeaconSnapshot(
                    pos.getX(),
                    pos.getY(),
                    pos.getZ(),
                    distanceSq,
                    beacon.shouldBeamRender() > 0.0F,
                    beacon.getField(0),
                    beacon.getField(1),
                    beacon.getField(2),
                    beacon.getField(3),
                    beacon.getField(4),
                    beacon.getField(5),
                    beacon.getField(6)
            ));
        }

        return result;
    }

    private JsonArray captureMap99x99(World world, EntityPlayerSP player) {
        JsonArray rows = new JsonArray();

        int centerX = player.getPosition().getX();
        int centerZ = player.getPosition().getZ();
        int baseY = player.getPosition().getY();

        // Each cell stores the block-state ID of the highest non-air block in
        // the player's immediate floor band, plus the block-state ID directly
        // above it.  This preserves the maze's top-block signature and enough
        // vertical information to identify pads/disabled cells without writing
        // thousands of verbose block names.
        for (int dz = -MAP_RADIUS; dz <= MAP_RADIUS; dz++) {
            JsonArray row = new JsonArray();

            for (int dx = -MAP_RADIUS; dx <= MAP_RADIUS; dx++) {
                int x = centerX + dx;
                int z = centerZ + dz;
                int floorState = 0;
                int aboveState = 0;
                int floorY = baseY;

                for (int y = baseY + 4; y >= baseY - 6; y--) {
                    BlockPos pos = new BlockPos(x, y, z);
                    if (!world.isBlockLoaded(pos, false)) {
                        continue;
                    }

                    int stateId = net.minecraft.block.Block.getStateId(world.getBlockState(pos));
                    if (stateId == 0) {
                        continue;
                    }

                    floorState = stateId;
                    floorY = y;
                    BlockPos above = new BlockPos(x, y + 1, z);
                    aboveState = net.minecraft.block.Block.getStateId(world.getBlockState(above));
                    break;
                }

                JsonArray cell = new JsonArray();
                cell.add(new com.google.gson.JsonPrimitive(dx));
                cell.add(new com.google.gson.JsonPrimitive(dz));
                cell.add(new com.google.gson.JsonPrimitive(floorY));
                cell.add(new com.google.gson.JsonPrimitive(floorState));
                cell.add(new com.google.gson.JsonPrimitive(aboveState));
                row.add(cell);
            }

            rows.add(row);
        }

        return rows;
    }

    private ScoreboardSnapshot readScoreboard(World world) {
        Scoreboard scoreboard = world.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);

        if (objective == null) {
            return ScoreboardSnapshot.empty();
        }

        List<ScoreLine> lines = new ArrayList<ScoreLine>();
        Collection<Score> scores = scoreboard.getSortedScores(objective);

        for (Score score : scores) {
            if (score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                continue;
            }

            String name = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(name);

            String formatted = team == null
                    ? name
                    : ScorePlayerTeam.formatPlayerName(team, name);

            lines.add(new ScoreLine(
                    stripFormatting(formatted),
                    score.getScorePoints()
            ));
        }

        String title = stripFormatting(objective.getDisplayName());

        return new ScoreboardSnapshot(title, lines);
    }

    private void openLog() {
        try {
            File directory = new File(MC.mcDataDir, "monstermazeai");
            if (!directory.exists() && !directory.mkdirs()) {
                throw new IOException("Unable to create " + directory);
            }

            String timestamp = new java.text.SimpleDateFormat(
                    "yyyyMMdd-HHmmss", Locale.ROOT
            ).format(new java.util.Date());

            logFile = new File(directory, "observations-" + timestamp + ".jsonl");
            writer = new BufferedWriter(new FileWriter(logFile, true));

            JsonObject header = new JsonObject();
            header.addProperty("type", "header");
            header.addProperty("format", "monstermazeai-1.8-observation-v2");
            header.addProperty("minecraft", "1.8.9");
            header.addProperty("forge", "11.15.1.2318");
            header.addProperty("mapSize", 99);
            header.addProperty("mapIntervalTicks", 20);
            writeLine(GSON.toJson(header));
        } catch (IOException e) {
            writer = null;
            System.err.println("[MonsterMazeAI/1.8] Failed to open observation log: " + e);
        }
    }

    private void writeLine(String line) {
        if (writer == null) {
            return;
        }

        try {
            writer.write(line);
            writer.newLine();

            if (tickCounter % 20 == 0) {
                writer.flush();
            }
        } catch (IOException e) {
            System.err.println("[MonsterMazeAI/1.8] Failed to write observation log: " + e);
            closeLog();
        }
    }

    public void closeLog() {
        if (writer == null) {
            return;
        }

        try {
            writer.flush();
            writer.close();
        } catch (IOException ignored) {
            // Nothing useful can be done during client shutdown/world unload.
        } finally {
            writer = null;
        }
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\\u00a7.", "");
    }

    public static final class Observation {
        public final boolean inMonsterMaze;
        public final double x;
        public final double y;
        public final double z;
        public final double vx;
        public final double vy;
        public final double vz;
        public final float yaw;
        public final float pitch;
        public final boolean grounded;
        public final float health;
        public final float maxHealth;
        public final ScoreboardSnapshot scoreboard;
        public final List<EntitySnapshot> entities;
        public final List<BeaconSnapshot> beacons;

        private Observation(
                boolean inMonsterMaze,
                EntityPlayerSP player,
                ScoreboardSnapshot scoreboard,
                List<EntitySnapshot> entities,
                List<BeaconSnapshot> beacons) {

            this.inMonsterMaze = inMonsterMaze;
            this.x = player.posX;
            this.y = player.posY;
            this.z = player.posZ;
            this.vx = player.motionX;
            this.vy = player.motionY;
            this.vz = player.motionZ;
            this.yaw = player.rotationYaw;
            this.pitch = player.rotationPitch;
            this.grounded = player.onGround;
            this.health = player.getHealth();
            this.maxHealth = player.getMaxHealth();
            this.scoreboard = scoreboard;
            this.entities = entities;
            this.beacons = beacons;
        }

        JsonObject toJson() {
            JsonObject root = new JsonObject();
            root.addProperty("inMonsterMaze", inMonsterMaze);

            JsonObject player = new JsonObject();
            player.addProperty("name", MC.thePlayer.getName());
            player.addProperty("uuid", MC.thePlayer.getUniqueID().toString());
            player.addProperty("x", x);
            player.addProperty("y", y);
            player.addProperty("z", z);
            player.addProperty("vx", vx);
            player.addProperty("vy", vy);
            player.addProperty("vz", vz);
            player.addProperty("yaw", yaw);
            player.addProperty("pitch", pitch);
            player.addProperty("grounded", grounded);
            player.addProperty("health", health);
            player.addProperty("maxHealth", maxHealth);
            player.addProperty("fallDistance", MC.thePlayer.fallDistance);
            player.addProperty("moveForward", MC.thePlayer.movementInput.moveForward);
            player.addProperty("moveStrafe", MC.thePlayer.movementInput.moveStrafe);
            player.addProperty("inputJump", MC.thePlayer.movementInput.jump);
            player.addProperty("inputSneak", MC.thePlayer.movementInput.sneak);
            player.addProperty("collidedHorizontally", MC.thePlayer.isCollidedHorizontally);
            player.addProperty("collidedVertically", MC.thePlayer.isCollidedVertically);
            player.addProperty("foodLevel", MC.thePlayer.getFoodStats().getFoodLevel());
            player.addProperty("foodSaturation", MC.thePlayer.getFoodStats().getSaturationLevel());
            player.addProperty("air", MC.thePlayer.getAir());
            player.addProperty("fire", MC.thePlayer.isBurning());
            player.addProperty("hurtTime", MC.thePlayer.hurtTime);
            player.addProperty("hurtResistantTime", MC.thePlayer.hurtResistantTime);
            player.addProperty("ticksExisted", MC.thePlayer.ticksExisted);
            player.addProperty("sprinting", MC.thePlayer.isSprinting());
            player.addProperty("sneaking", MC.thePlayer.isSneaking());
            player.addProperty("usingItem", MC.thePlayer.isUsingItem());
            player.addProperty("dimension", MC.theWorld.provider.getDimensionId());
            player.addProperty("worldTime", MC.theWorld.getWorldTime());
            root.add("player", player);

            JsonObject scoreboardJson = new JsonObject();
            scoreboardJson.addProperty("title", scoreboard.title);
            JsonArray scoreLines = new JsonArray();
            for (ScoreLine line : scoreboard.lines) {
                JsonObject entry = new JsonObject();
                entry.addProperty("text", line.text);
                entry.addProperty("score", line.score);
                scoreLines.add(entry);
            }
            scoreboardJson.add("lines", scoreLines);
            root.add("scoreboard", scoreboardJson);

            JsonArray entityJson = new JsonArray();
            for (EntitySnapshot entity : entities) {
                entityJson.add(entity.toJson());
            }
            root.add("entities", entityJson);

            JsonArray beaconJson = new JsonArray();
            for (BeaconSnapshot beacon : beacons) {
                beaconJson.add(beacon.toJson());
            }
            root.add("beacons", beaconJson);

            JsonArray inventory = new JsonArray();
            for (int slot = 0; slot < MC.thePlayer.inventory.getSizeInventory(); slot++) {
                ItemStack stack = MC.thePlayer.inventory.getStackInSlot(slot);
                if (stack == null) {
                    continue;
                }

                JsonObject item = new JsonObject();
                item.addProperty("slot", slot);
                item.addProperty("item", String.valueOf(stack.getItem()));
                item.addProperty("displayName", stack.getDisplayName());
                item.addProperty("stackSize", stack.stackSize);
                item.addProperty("metadata", stack.getMetadata());
                inventory.add(item);
            }
            root.add("inventory", inventory);

            ItemStack held = MC.thePlayer.getHeldItem();
            root.addProperty("heldItem", held == null ? "" : held.getDisplayName());
            root.addProperty("heldItemUseCount", MC.thePlayer.getItemInUseCount());
            root.addProperty("activeItem", MC.thePlayer.getItemInUse() == null ? "" : MC.thePlayer.getItemInUse().getDisplayName());
            root.addProperty("flying", MC.thePlayer.capabilities.isFlying);
            root.addProperty("allowFlying", MC.thePlayer.capabilities.allowFlying);
            root.addProperty("creativeMode", MC.thePlayer.capabilities.isCreativeMode);

            JsonArray effects = new JsonArray();
            for (Object effectObject : MC.thePlayer.getActivePotionEffects()) {
                net.minecraft.potion.PotionEffect effect = (net.minecraft.potion.PotionEffect) effectObject;
                JsonObject effectJson = new JsonObject();
                effectJson.addProperty("potionId", effect.getPotionID());
                effectJson.addProperty("duration", effect.getDuration());
                effectJson.addProperty("amplifier", effect.getAmplifier());
                effects.add(effectJson);
            }
            root.add("potionEffects", effects);

            return root;
        }

        public String toLogLine() {
            return String.format(
                    Locale.ROOT,
                    "maze=%s player=(%.2f,%.2f,%.2f) vel=(%.3f,%.3f,%.3f) " +
                            "yaw=%.1f pitch=%.1f grounded=%s hp=%.1f/%.1f " +
                            "entities=%d beacons=%d scoreboard=\"%s\"",
                    inMonsterMaze,
                    x, y, z,
                    vx, vy, vz,
                    yaw, pitch,
                    grounded,
                    health, maxHealth,
                    entities.size(),
                    beacons.size(),
                    scoreboard.title
            );
        }
    }

    public static final class EntitySnapshot {
        public final int id;
        public final String type;
        public final String name;
        public final String uuid;
        public final double x;
        public final double y;
        public final double z;
        public final double vx;
        public final double vy;
        public final double vz;
        public final float yaw;
        public final float pitch;
        public final boolean grounded;
        public final float width;
        public final float height;
        public final float health;
        public final float maxHealth;
        public final double distance;

        EntitySnapshot(
                int id,
                String type,
                String name,
                String uuid,
                double x,
                double y,
                double z,
                double vx,
                double vy,
                double vz,
                float yaw,
                float pitch,
                boolean grounded,
                float width,
                float height,
                float health,
                float maxHealth,
                double distance) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.uuid = uuid;
            this.x = x;
            this.y = y;
            this.z = z;
            this.vx = vx;
            this.vy = vy;
            this.vz = vz;
            this.yaw = yaw;
            this.pitch = pitch;
            this.grounded = grounded;
            this.width = width;
            this.height = height;
            this.health = health;
            this.maxHealth = maxHealth;
            this.distance = distance;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("id", id);
            json.addProperty("type", type);
            json.addProperty("name", name);
            json.addProperty("uuid", uuid);
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            json.addProperty("vx", vx);
            json.addProperty("vy", vy);
            json.addProperty("vz", vz);
            json.addProperty("yaw", yaw);
            json.addProperty("pitch", pitch);
            json.addProperty("grounded", grounded);
            json.addProperty("width", width);
            json.addProperty("height", height);
            json.addProperty("health", health);
            json.addProperty("maxHealth", maxHealth);
            json.addProperty("distance", distance);
            return json;
        }
    }

    public static final class BeaconSnapshot {
        public final int x;
        public final int y;
        public final int z;
        public final double distanceSq;
        public final boolean beam;
        public final int field0;
        public final int field1;
        public final int field2;
        public final int field3;
        public final int field4;
        public final int field5;
        public final int field6;

        BeaconSnapshot(
                int x,
                int y,
                int z,
                double distanceSq,
                boolean beam,
                int field0,
                int field1,
                int field2,
                int field3,
                int field4,
                int field5,
                int field6) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.distanceSq = distanceSq;
            this.beam = beam;
            this.field0 = field0;
            this.field1 = field1;
            this.field2 = field2;
            this.field3 = field3;
            this.field4 = field4;
            this.field5 = field5;
            this.field6 = field6;
        }

        JsonObject toJson() {
            JsonObject json = new JsonObject();
            json.addProperty("x", x);
            json.addProperty("y", y);
            json.addProperty("z", z);
            json.addProperty("distanceSq", distanceSq);
            json.addProperty("beam", beam);
            json.addProperty("field0", field0);
            json.addProperty("field1", field1);
            json.addProperty("field2", field2);
            json.addProperty("field3", field3);
            json.addProperty("field4", field4);
            json.addProperty("field5", field5);
            json.addProperty("field6", field6);
            return json;
        }
    }

    public static final class ScoreboardSnapshot {
        public final String title;
        public final List<ScoreLine> lines;

        private ScoreboardSnapshot(String title, List<ScoreLine> lines) {
            this.title = title;
            this.lines = lines;
        }

        static ScoreboardSnapshot empty() {
            return new ScoreboardSnapshot("", new ArrayList<ScoreLine>());
        }

        boolean looksLikeMonsterMaze() {
            String lowerTitle = title.toLowerCase(Locale.ROOT);

            if (lowerTitle.contains("monster maze")) {
                return true;
            }

            for (ScoreLine line : lines) {
                String text = line.text.toLowerCase(Locale.ROOT);

                if (text.contains("safe pad") ||
                        text.equals("stage") ||
                        text.contains("monster maze")) {
                    return true;
                }
            }

            return false;
        }

        @Override
        public String toString() {
            return "{" +
                    "title='" + title + '\'' +
                    ", lines=" + lines +
                    '}';
        }
    }

    public static final class ScoreLine {
        public final String text;
        public final int score;

        private ScoreLine(String text, int score) {
            this.text = text;
            this.score = score;
        }

        @Override
        public String toString() {
            return "'" + text + "'=" + score;
        }
    }
}
