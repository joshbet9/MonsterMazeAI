package me.monstermazeai.minecraft.v18;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.PlayerState;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.entity.monster.EntitySnowman;
import net.minecraft.item.ItemStack;
import net.minecraft.scoreboard.Score;
import net.minecraft.scoreboard.ScoreObjective;
import net.minecraft.scoreboard.ScorePlayerTeam;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.util.BlockPos;
import net.minecraft.world.World;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;

public final class Minecraft18Observer {
    private static final Minecraft MC = Minecraft.getMinecraft();
    private static final int MAZE_SIZE = 99;
    private static final int HALF_MAZE = 49;
    private static final int SCAN_RADIUS = 64;

    private int ticksSinceLastLog;

    public void tick() {
        if (MC.theWorld == null || MC.thePlayer == null) {
            return;
        }

        ticksSinceLastLog++;
        if (ticksSinceLastLog < 20) {
            return;
        }
        ticksSinceLastLog = 0;

        Observation observation = observe();
        if (!observation.inMonsterMaze) {
            return;
        }

        System.out.println("[MonsterMazeAI/1.8] " + observation.toLogLine());
    }

    public Observation observe() {
        EntityPlayerSP player = MC.thePlayer;
        World world = MC.theWorld;

        ScoreboardSnapshot scoreboard = readScoreboard(world);
        boolean mazeScoreboard = scoreboard.looksLikeMonsterMaze();

        BlockPos center = findMazeCenter(world, player);
        PadObservation pad = center == null
                ? findActivePadWithoutCenter(world, player)
                : findActivePad(world, player, center);

        int[][] raw = new int[MAZE_SIZE][MAZE_SIZE];
        boolean mazeDetected = center != null && readMaze(world, center, raw);

        GameState state = new GameState();
        state.tick = world.getTotalWorldTime();
        state.stage = Math.max(1, scoreboard.stage);
        state.phaseTicksRemaining = Math.max(0, scoreboard.safePadSeconds * 20);
        state.liveSeconds = (int) Math.max(0, (world.getTotalWorldTime() / 20L));
        state.alive = player.getHealth() > 0.0F;
        state.maze = mazeDetected ? new MazeModel(raw) : null;

        PlayerState ps = state.player;
        ps.x = player.posX;
        ps.y = player.posY;
        ps.z = player.posZ;
        ps.vx = player.motionX;
        ps.vy = player.motionY;
        ps.vz = player.motionZ;
        ps.yaw = player.rotationYaw;
        ps.pitch = player.rotationPitch;
        ps.grounded = player.onGround;
        ps.health = player.getHealth();
        ps.maxHealth = player.getMaxHealth();

        state.kit = detectKit(player);
        ps.jumpCharges = detectJumpCharges(player, state.kit);

        if (pad != null && pad.row >= 0) {
            state.activePadRow = pad.row;
            state.activePadColumn = pad.column;
            if (state.maze != null) {
                disablePadArea(state.maze, pad.row, pad.column);
            }
        }

        int id = 0;
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntitySnowman) || entity == player) {
                continue;
            }

            MonsterState monster = new MonsterState(id++, entity.posX, entity.posY, entity.posZ);
            monster.vx = entity.motionX;
            monster.vy = entity.motionY;
            monster.vz = entity.motionZ;
            monster.removed = entity.isDead;
            state.monsters.add(monster);

            if (id >= 256) {
                break;
            }
        }

        return new Observation(
                mazeScoreboard || mazeDetected || pad != null || !state.monsters.isEmpty(),
                mazeDetected,
                center,
                pad,
                scoreboard,
                state
        );
    }

    private boolean readMaze(World world, BlockPos center, int[][] raw) {
        BlockSignature top = findDominantTopBlock(world, center);
        if (top == null) {
            return false;
        }

        int pathCount = 0;
        for (int row = 0; row < MAZE_SIZE; row++) {
            for (int col = 0; col < MAZE_SIZE; col++) {
                int x = center.getX() - HALF_MAZE + row;
                int z = center.getZ() - HALF_MAZE + col;
                if (matches(world, new BlockPos(x, center.getY() - 1, z), top)) {
                    raw[row][col] = 1;
                    pathCount++;
                }
            }
        }
        return pathCount >= 100;
    }

    private BlockSignature findDominantTopBlock(World world, BlockPos center) {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();

        for (int row = 0; row < MAZE_SIZE; row++) {
            for (int col = 0; col < MAZE_SIZE; col++) {
                BlockPos pos = new BlockPos(
                        center.getX() - HALF_MAZE + row,
                        center.getY() - 1,
                        center.getZ() - HALF_MAZE + col
                );
                net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
                net.minecraft.block.Block block = state.getBlock();
                if (block == net.minecraft.init.Blocks.air
                        || block == net.minecraft.init.Blocks.stained_hardened_clay
                        || block == net.minecraft.init.Blocks.beacon) {
                    continue;
                }

                int meta = block.getMetaFromState(state);
                String key = block.getRegistryName() + ":" + meta;
                Integer count = counts.get(key);
                counts.put(key, count == null ? 1 : count + 1);
            }
        }

        String best = null;
        int bestCount = 0;
        for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                best = entry.getKey();
                bestCount = entry.getValue();
            }
        }

        if (best == null) {
            return null;
        }

        String[] parts = best.split(":");
        net.minecraft.block.Block block = net.minecraft.block.Block.getBlockFromName(parts[0]);
        if (block == null) {
            return null;
        }
        return new BlockSignature(block, Integer.parseInt(parts[1]));
    }

    private BlockPos findMazeCenter(World world, EntityPlayerSP player) {
        int y = (int) Math.floor(player.posY) - 1;
        BlockPos best = null;
        int bestScore = 0;

        for (int x = player.getPosition().getX() - SCAN_RADIUS; x <= player.getPosition().getX() + SCAN_RADIUS; x++) {
            for (int z = player.getPosition().getZ() - SCAN_RADIUS; z <= player.getPosition().getZ() + SCAN_RADIUS; z++) {
                if (world.getBlockState(new BlockPos(x, y, z)).getBlock() != net.minecraft.init.Blocks.stained_hardened_clay) {
                    continue;
                }

                int score = 0;
                for (int dx = -4; dx <= 4; dx++) {
                    for (int dz = -4; dz <= 4; dz++) {
                        if (world.getBlockState(new BlockPos(x + dx, y, z + dz)).getBlock()
                                == net.minecraft.init.Blocks.stained_hardened_clay) {
                            score++;
                        }
                    }
                }

                if (score > bestScore) {
                    bestScore = score;
                    best = new BlockPos(x, y + 1, z);
                }
            }
        }

        return bestScore >= 40 ? best : null;
    }

    private PadObservation findActivePad(World world, EntityPlayerSP player, BlockPos center) {
        int radius = 70;
        PadObservation best = null;

        for (int x = center.getX() - radius; x <= center.getX() + radius; x++) {
            for (int z = center.getZ() - radius; z <= center.getZ() + radius; z++) {
                BlockPos beacon = new BlockPos(x, center.getY() - 1, z);
                if (world.getBlockState(beacon).getBlock() != net.minecraft.init.Blocks.beacon) {
                    continue;
                }

                int row = x - (center.getX() - HALF_MAZE);
                int col = z - (center.getZ() - HALF_MAZE);
                if (row < 0 || row >= MAZE_SIZE || col < 0 || col >= MAZE_SIZE) {
                    continue;
                }

                double distance = player.getDistanceSq(x + 0.5, center.getY(), z + 0.5);
                if (best == null || distance < best.distanceSq) {
                    best = new PadObservation(row, col, distance);
                }
            }
        }

        return best;
    }

    private PadObservation findActivePadWithoutCenter(World world, EntityPlayerSP player) {
        int px = player.getPosition().getX();
        int pz = player.getPosition().getZ();
        int y = player.getPosition().getY() - 1;

        for (int x = px - SCAN_RADIUS; x <= px + SCAN_RADIUS; x++) {
            for (int z = pz - SCAN_RADIUS; z <= pz + SCAN_RADIUS; z++) {
                if (world.getBlockState(new BlockPos(x, y, z)).getBlock() == net.minecraft.init.Blocks.beacon) {
                    return new PadObservation(-1, -1, player.getDistanceSq(x + 0.5, y + 1.0, z + 0.5));
                }
            }
        }
        return null;
    }

    private void disablePadArea(MazeModel maze, int row, int col) {
        for (int r = row - 2; r <= row + 2; r++) {
            for (int c = col - 2; c <= col + 2; c++) {
                if (r >= 0 && r < MAZE_SIZE && c >= 0 && c < MAZE_SIZE) {
                    maze.setDisabled(r, c, true);
                }
            }
        }
    }

    private Kit detectKit(EntityPlayerSP player) {
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null || !stack.hasDisplayName()) continue;
            String name = stack.getDisplayName().toLowerCase(Locale.ROOT);
            if (name.contains("jumps remaining")) return Kit.JUMPER;
            if (name.contains("repulse")) return Kit.REPULSOR;
            if (name.contains("cryo") || name.contains("slow")) return Kit.SLOWBALLER;
            if (name.contains("body rush")) return Kit.BODY_BUILDER;
        }
        return Kit.JUMPER;
    }

    private int detectJumpCharges(EntityPlayerSP player, Kit kit) {
        if (kit != Kit.JUMPER) return 0;
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null || !stack.hasDisplayName()) continue;
            if (stack.getDisplayName().toLowerCase(Locale.ROOT).contains("jumps remaining")) {
                return stack.stackSize;
            }
        }
        return 0;
    }

    private boolean matches(World world, BlockPos pos, BlockSignature signature) {
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        return state.getBlock() == signature.block
                && signature.meta == signature.block.getMetaFromState(state);
    }

    private ScoreboardSnapshot readScoreboard(World world) {
        Scoreboard scoreboard = world.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) return ScoreboardSnapshot.empty();

        List<ScoreLine> lines = new ArrayList<ScoreLine>();
        Collection<Score> scores = scoreboard.getSortedScores(objective);
        for (Score score : scores) {
            if (score.getPlayerName() == null || score.getPlayerName().startsWith("#")) continue;
            String name = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(name);
            String formatted = team == null ? name : ScorePlayerTeam.formatPlayerName(team, name);
            lines.add(new ScoreLine(stripFormatting(formatted), score.getScorePoints()));
        }

        String title = stripFormatting(objective.getDisplayName());
        int safePadSeconds = 0;
        int stage = 1;

        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).text.toLowerCase(Locale.ROOT);
            if (line.contains("safe pad") && i + 1 < lines.size()) {
                Integer seconds = firstInteger(lines.get(i + 1).text);
                if (seconds != null) safePadSeconds = seconds;
            }
            if (line.equals("stage") && i + 1 < lines.size()) {
                Integer parsedStage = firstInteger(lines.get(i + 1).text);
                if (parsedStage != null) stage = parsedStage;
            }
        }

        return new ScoreboardSnapshot(title, safePadSeconds, stage, lines);
    }

    private static Integer firstInteger(String text) {
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (Character.isDigit(ch)) digits.append(ch);
            else if (digits.length() > 0) break;
        }
        if (digits.length() == 0) return null;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String stripFormatting(String text) {
        return text == null ? "" : text.replaceAll("\\u00a7.", "");
    }

    public static final class Observation {
        public final boolean inMonsterMaze;
        public final boolean mazeDetected;
        public final BlockPos center;
        public final PadObservation pad;
        public final ScoreboardSnapshot scoreboard;
        public final GameState state;

        private Observation(boolean inMonsterMaze, boolean mazeDetected, BlockPos center,
                            PadObservation pad, ScoreboardSnapshot scoreboard, GameState state) {
            this.inMonsterMaze = inMonsterMaze;
            this.mazeDetected = mazeDetected;
            this.center = center;
            this.pad = pad;
            this.scoreboard = scoreboard;
            this.state = state;
        }

        public String toLogLine() {
            return String.format(Locale.ROOT,
                    "player=(%.2f,%.2f,%.2f) vel=(%.3f,%.3f,%.3f) hp=%.1f kit=%s jumps=%d " +
                    "center=%s pad=%s stage=%d timer=%ds maze=%s monsters=%d scoreboard=%s",
                    state.player.x, state.player.y, state.player.z,
                    state.player.vx, state.player.vy, state.player.vz, state.player.health,
                    state.kit, state.player.jumpCharges,
                    center == null ? "none" : center.toString(),
                    pad == null ? "none" : pad.toString(),
                    scoreboard.stage, scoreboard.safePadSeconds,
                    mazeDetected, state.monsters.size(), scoreboard.title);
        }
    }

    public static final class PadObservation {
        public final int row;
        public final int column;
        public final double distanceSq;

        private PadObservation(int row, int column, double distanceSq) {
            this.row = row;
            this.column = column;
            this.distanceSq = distanceSq;
        }

        @Override
        public String toString() {
            return String.format(Locale.ROOT, "(%d,%d)", row, column);
        }
    }

    public static final class ScoreboardSnapshot {
        public final String title;
        public final int safePadSeconds;
        public final int stage;
        public final List<ScoreLine> lines;

        private ScoreboardSnapshot(String title, int safePadSeconds, int stage, List<ScoreLine> lines) {
            this.title = title;
            this.safePadSeconds = safePadSeconds;
            this.stage = stage;
            this.lines = lines;
        }

        static ScoreboardSnapshot empty() {
            return new ScoreboardSnapshot("", 0, 1, java.util.Collections.<ScoreLine>emptyList());
        }

        boolean looksLikeMonsterMaze() {
            if (title.toLowerCase(Locale.ROOT).contains("monster maze")) return true;
            for (ScoreLine line : lines) {
                String text = line.text.toLowerCase(Locale.ROOT);
                if (text.contains("safe pad") || text.equals("stage")) return true;
            }
            return false;
        }
    }

    public static final class ScoreLine {
        public final String text;
        public final int score;

        private ScoreLine(String text, int score) {
            this.text = text;
            this.score = score;
        }
    }

    private static final class BlockSignature {
        private final net.minecraft.block.Block block;
        private final int meta;

        private BlockSignature(net.minecraft.block.Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }

        @Override
        public String toString() {
            return block.getRegistryName() + ":" + meta;
        }
    }
}
