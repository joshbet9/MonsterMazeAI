package me.monstermazeai.minecraft.v18;

import me.monstermazeai.kit.Kit;
import me.monstermazeai.adapter.WorldObservation;
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
    private static final int PAD_SCAN_RADIUS = 70;

    private int ticksSinceLastLog;
    private long gameStartWorldTick = -1L;
    private BlockPos cachedCenter;
    private boolean previouslyInMonsterMaze;

    public void tick() {
        if (MC.theWorld == null || MC.thePlayer == null) {
            reset();
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

        Minecraft18ObservationRules.ScoreboardData scoreboard = readScoreboard(world);
        boolean mazeScoreboard = Minecraft18ObservationRules.looksLikeMonsterMaze(scoreboard);

        BlockPos center = findMazeCenter(world, player, mazeScoreboard);
        PadObservation pad = center == null
                ? findActivePadWithoutCenter(world, player)
                : findActivePad(world, player, center);

        int[][] raw = new int[MAZE_SIZE][MAZE_SIZE];
        boolean mazeDetected = center != null && readMaze(world, center, raw);

        boolean inMonsterMaze = mazeScoreboard || mazeDetected || pad != null;
        if (!inMonsterMaze) {
            for (Entity entity : world.loadedEntityList) {
                if (entity instanceof EntitySnowman && !entity.isDead) {
                    inMonsterMaze = true;
                    break;
                }
            }
        }

        if (inMonsterMaze && !previouslyInMonsterMaze) {
            gameStartWorldTick = world.getTotalWorldTime();
        } else if (!inMonsterMaze) {
            gameStartWorldTick = -1L;
        }
        previouslyInMonsterMaze = inMonsterMaze;

        long worldTick = world.getTotalWorldTime();
        int stage = Math.max(1, scoreboard.stage);
        int safePadSeconds = Math.max(0, scoreboard.safePadSeconds);
        int liveSeconds = gameStartWorldTick < 0
                ? 0
                : (int) Math.max(0, (worldTick - gameStartWorldTick) / 20L);
        boolean alive = player.getHealth() > 0.0F;
        boolean completed = scoreboard.completed;
        boolean matchedMaze = inMonsterMaze && !completed;

        List<String> displayNames = new ArrayList<String>();
        List<Integer> stackSizes = new ArrayList<Integer>();
        for (int slot = 0; slot < player.inventory.getSizeInventory(); slot++) {
            ItemStack stack = player.inventory.getStackInSlot(slot);
            if (stack == null) {
                continue;
            }
            displayNames.add(stack.hasDisplayName() ? stack.getDisplayName() : "");
            stackSizes.add(stack.stackSize);
        }

        Kit kit = Minecraft18ObservationRules.detectKit(displayNames);
        int jumpCharges = Minecraft18ObservationRules.detectJumpCharges(
                displayNames, kit, stackSizes);
        int abilityCharges = detectAbilityCharges(displayNames, stackSizes, kit);
        boolean padReached = pad != null && pad.row >= 0 && isOnPad(player, pad, center);

        if (pad != null && pad.row >= 0 && mazeDetected) {
            disablePadArea(raw, pad.row, pad.column);
        }

        List<WorldObservation.Monster> monsters = new ArrayList<WorldObservation.Monster>();
        int id = 0;
        for (Entity entity : world.loadedEntityList) {
            if (!(entity instanceof EntitySnowman)) {
                continue;
            }
            monsters.add(new WorldObservation.Monster(
                    id++, entity.posX, entity.posY, entity.posZ,
                    entity.motionX, entity.motionY, entity.motionZ, entity.isDead));
            if (id >= 256) {
                break;
            }
        }

        WorldObservation observation = new WorldObservation(
                worldTick, matchedMaze, mazeDetected, alive, completed,
                stage, safePadSeconds, liveSeconds,
                new WorldObservation.Player(
                        player.posX, player.posY, player.posZ,
                        player.motionX, player.motionY, player.motionZ,
                        player.rotationYaw, player.rotationPitch, player.onGround,
                        player.getHealth(), player.getMaxHealth()),
                kit, jumpCharges, abilityCharges,
                center == null ? null : new WorldObservation.BlockPoint(center.getX(), center.getY(), center.getZ()),
                pad == null ? null : new WorldObservation.Pad(pad.row, pad.column, pad.distanceSq, padReached),
                raw, monsters, scoreboard.title, scoreboard.lines);

        return new Observation(
                matchedMaze,
                mazeDetected,
                center,
                pad,
                scoreboard,
                observation
        );
    }

    private void reset() {
        ticksSinceLastLog = 0;
        gameStartWorldTick = -1L;
        cachedCenter = null;
        previouslyInMonsterMaze = false;
    }

    private int detectAbilityCharges(List<String> names, List<Integer> sizes, Kit kit) {
        if (kit == Kit.SLOWBALLER || kit == Kit.REPULSOR || kit == Kit.BODY_BUILDER) {
            int best = 0;
            for (int i = 0; i < names.size() && i < sizes.size(); i++) {
                String name = names.get(i).toLowerCase(Locale.ROOT);
                if ((kit == Kit.SLOWBALLER && name.contains("snowball"))
                        || (kit == Kit.REPULSOR && name.contains("repuls"))
                        || (kit == Kit.BODY_BUILDER && name.contains("body rush"))) {
                    best = Math.max(best, sizes.get(i));
                }
            }
            return best;
        }
        return 0;
    }

    private boolean isOnPad(EntityPlayerSP player, PadObservation pad, BlockPos center) {
        if (center == null || pad.row < 0) {
            return false;
        }
        double x = center.getX() - HALF_MAZE + pad.row + 0.5;
        double z = center.getZ() - HALF_MAZE + pad.column + 0.5;
        double dx = player.posX - x;
        double dz = player.posZ - z;
        return dx * dx + dz * dz <= 2.25;
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
                net.minecraft.block.state.IBlockState blockState = world.getBlockState(pos);
                net.minecraft.block.Block block = blockState.getBlock();
                if (block == net.minecraft.init.Blocks.air
                        || block == net.minecraft.init.Blocks.stained_hardened_clay
                        || block == net.minecraft.init.Blocks.beacon) {
                    continue;
                }

                int meta = block.getMetaFromState(blockState);
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

    private BlockPos findMazeCenter(World world, EntityPlayerSP player, boolean scoreboardDetected) {
        if (cachedCenter != null && isCenterPlausible(world, cachedCenter)) {
            return cachedCenter;
        }

        int y = (int) Math.floor(player.posY) - 1;
        BlockPos best = null;
        int bestScore = 0;

        for (int x = player.getPosition().getX() - SCAN_RADIUS;
             x <= player.getPosition().getX() + SCAN_RADIUS; x++) {
            for (int z = player.getPosition().getZ() - SCAN_RADIUS;
                 z <= player.getPosition().getZ() + SCAN_RADIUS; z++) {
                if (world.getBlockState(new BlockPos(x, y, z)).getBlock()
                        != net.minecraft.init.Blocks.stained_hardened_clay) {
                    continue;
                }

                int score = 0;
                for (int dx = -3; dx <= 3; dx++) {
                    for (int dz = -3; dz <= 3; dz++) {
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

        if (bestScore >= (scoreboardDetected ? 25 : 40)) {
            cachedCenter = best;
        }
        return cachedCenter;
    }

    private boolean isCenterPlausible(World world, BlockPos center) {
        int y = center.getY() - 1;
        int score = 0;
        for (int dx = -2; dx <= 2; dx++) {
            for (int dz = -2; dz <= 2; dz++) {
                if (world.getBlockState(new BlockPos(center.getX() + dx, y, center.getZ() + dz)).getBlock()
                        == net.minecraft.init.Blocks.stained_hardened_clay) {
                    score++;
                }
            }
        }
        return score >= 8;
    }

    private PadObservation findActivePad(World world, EntityPlayerSP player, BlockPos center) {
        PadObservation best = null;

        for (int x = center.getX() - PAD_SCAN_RADIUS; x <= center.getX() + PAD_SCAN_RADIUS; x++) {
            for (int z = center.getZ() - PAD_SCAN_RADIUS; z <= center.getZ() + PAD_SCAN_RADIUS; z++) {
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
                if (world.getBlockState(new BlockPos(x, y, z)).getBlock()
                        == net.minecraft.init.Blocks.beacon) {
                    return new PadObservation(-1, -1,
                            player.getDistanceSq(x + 0.5, y + 1.0, z + 0.5));
                }
            }
        }
        return null;
    }

    private void disablePadArea(int[][] maze, int row, int col) {
        for (int r = row - 2; r <= row + 2; r++) {
            for (int c = col - 2; c <= col + 2; c++) {
                if (r >= 0 && r < MAZE_SIZE && c >= 0 && c < MAZE_SIZE) {
                    maze[r][c] = 0;
                }
            }
        }
    }

    private boolean matches(World world, BlockPos pos, BlockSignature signature) {
        net.minecraft.block.state.IBlockState state = world.getBlockState(pos);
        return state.getBlock() == signature.block
                && signature.meta == signature.block.getMetaFromState(state);
    }

    private Minecraft18ObservationRules.ScoreboardData readScoreboard(World world) {
        Scoreboard scoreboard = world.getScoreboard();
        ScoreObjective objective = scoreboard.getObjectiveInDisplaySlot(1);
        if (objective == null) {
            return Minecraft18ObservationRules.parseScoreboard("", new ArrayList<String>());
        }

        List<String> lines = new ArrayList<String>();
        Collection<Score> scores = scoreboard.getSortedScores(objective);
        for (Score score : scores) {
            if (score.getPlayerName() == null || score.getPlayerName().startsWith("#")) {
                continue;
            }
            String name = score.getPlayerName();
            ScorePlayerTeam team = scoreboard.getPlayersTeam(name);
            String formatted = team == null ? name : ScorePlayerTeam.formatPlayerName(team, name);
            lines.add(formatted);
        }

        return Minecraft18ObservationRules.parseScoreboard(objective.getDisplayName(), lines);
    }

    private static final class BlockSignature {
        private final net.minecraft.block.Block block;
        private final int meta;

        private BlockSignature(net.minecraft.block.Block block, int meta) {
            this.block = block;
            this.meta = meta;
        }
    }

    public static final class Observation {
        public final boolean inMonsterMaze;
        public final boolean mazeDetected;
        public final BlockPos center;
        public final PadObservation pad;
        public final Minecraft18ObservationRules.ScoreboardData scoreboard;
        public final WorldObservation state;

        private Observation(boolean inMonsterMaze, boolean mazeDetected, BlockPos center,
                            PadObservation pad,
                            Minecraft18ObservationRules.ScoreboardData scoreboard,
                            WorldObservation state) {
            this.inMonsterMaze = inMonsterMaze;
            this.mazeDetected = mazeDetected;
            this.center = center;
            this.pad = pad;
            this.scoreboard = scoreboard;
            this.state = state;
        }

        public String toLogLine() {
            return String.format(Locale.ROOT,
                    "player=(%.2f,%.2f,%.2f) vel=(%.3f,%.3f,%.3f) hp=%.1f kit=%s jumps=%d "
                            + "center=%s pad=%s stage=%d timer=%ds maze=%s monsters=%d scoreboard=%s",
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
}
