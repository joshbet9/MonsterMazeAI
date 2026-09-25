package me.monstermazeai.minecraft.v18;

import me.monstermazeai.kit.Kit;
import me.monstermazeai.adapter.LegacyWorldObservation;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.Entity;
import net.minecraft.item.ItemStack;
import net.minecraft.init.Blocks;
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
    private static final int CENTER_SEARCH_RADIUS = 32;
    // Eye of Ender places the logical arena centre at world X/Z 0,0.
    // Try that authoritative location first, then retain a broader physical fallback
    // for maps/test worlds that are translated away from the origin.
    private static final int KNOWN_CENTER_X = 0;
    private static final int KNOWN_CENTER_Z = 0;
    private static final int PAD_SCAN_RADIUS = 70;
    private static final int CENTER_ANCHOR_RADIUS = 6;
    private static final int SAFE_PAD_RADIUS = 2;

    private int ticksSinceLastMazeRefresh;
    private int ticksSinceLastPadRefresh;
    private PadObservation cachedPad;
    private BlockPos cachedPadCenter;
    private int[][] cachedMaze = new int[MAZE_SIZE][MAZE_SIZE];
    private boolean cachedMazeDetected;
    private int cachedMazePattern = -1;
    private long gameStartWorldTick = -1L;
    private BlockPos cachedCenter;
    private boolean previouslyInMonsterMaze;

    public void tick() {
        if (MC.theWorld == null || MC.thePlayer == null) {
            reset();
            return;
        }

        Observation observation = observe();
        System.out.println("[MonsterMazeAI/1.8] " + observation.toLogLine());
        if (observation.mazeDetected && ticksSinceLastMazeRefresh == 0) {
            System.out.println("[MonsterMazeAI/1.8] " + observation.toMazeLogLine());
        }
    }

    public Observation observe() {
        EntityPlayerSP player = MC.thePlayer;
        World world = MC.theWorld;

        Minecraft18ObservationRules.ScoreboardData scoreboard = readScoreboard(world);
        boolean mazeScoreboard = Minecraft18ObservationRules.looksLikeMonsterMaze(scoreboard);

        BlockPos center = findMazeCenter(world, player, mazeScoreboard);
        if (center == null && !mazeScoreboard && cachedCenter != null
                && Math.abs(player.posY - cachedCenter.getY()) > 20.0D) {
            clearRoundState();
            center = null;
        }
        ticksSinceLastPadRefresh++;
        if (center == null) {
            cachedPad = findActivePadWithoutCenter(world, player);
            cachedPadCenter = null;
            ticksSinceLastPadRefresh = 0;
        } else if (cachedPadCenter == null || !cachedPadCenter.equals(center) || ticksSinceLastPadRefresh >= 5) {
            cachedPad = findActivePad(world, player, center);
            cachedPadCenter = center;
            ticksSinceLastPadRefresh = 0;
        }
        PadObservation pad = cachedPad;
        if (pad != null && center != null && pad.row >= 0) {
            double x = center.getX() - HALF_MAZE + pad.row + 0.5;
            double z = center.getZ() - HALF_MAZE + pad.column + 0.5;
            pad = new PadObservation(pad.row, pad.column,
                    player.getDistanceSq(x, center.getY(), z));
        }

        ticksSinceLastMazeRefresh++;
        // Keep the authoritative pattern cached for the whole round. The source mutates the center
        // platform and replaces a 5x5 area for each SafePad, so re-matching the live grid later
        // would eventually create false negatives.
        boolean refreshMaze = center != null && !cachedMazeDetected;
        if (refreshMaze) {
            int[][] refreshed = new int[MAZE_SIZE][MAZE_SIZE];
            cachedMazeDetected = readMaze(world, center, refreshed);
            if (cachedMazeDetected) {
                cachedMaze = refreshed;
            } else {
                cachedMaze = new int[MAZE_SIZE][MAZE_SIZE];
            }
            ticksSinceLastMazeRefresh = 0;
        }
        int[][] raw = cachedMaze;
        boolean mazeDetected = cachedMazeDetected && center != null;

        boolean inMonsterMaze = mazeScoreboard || mazeDetected || pad != null;
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
        
        List<LegacyWorldObservation.Monster> monsters = new ArrayList<LegacyWorldObservation.Monster>();
        for (Entity entity : world.loadedEntityList) {
            String visualType = MonsterSkinTypes.visualType(entity);
            if (visualType == null) {
                continue;
            }
            monsters.add(new LegacyWorldObservation.Monster(
                    entity.getEntityId(), MonsterSkinTypes.GAMEPLAY_TYPE, visualType,
                    entity.posX, entity.posY, entity.posZ,
                    entity.motionX, entity.motionY, entity.motionZ, entity.isDead));
            if (monsters.size() >= 256) {
                break;
            }
        }

        LegacyWorldObservation observation = new LegacyWorldObservation(
                worldTick, matchedMaze, mazeDetected,
                cachedMazePattern < 0 ? -1 : cachedMazePattern + 1,
                alive, completed, stage, safePadSeconds, liveSeconds,
                new LegacyWorldObservation.Player(
                        player.posX, player.posY, player.posZ,
                        player.motionX, player.motionY, player.motionZ,
                        player.rotationYaw, player.rotationPitch, player.onGround,
                        player.getHealth(), player.getMaxHealth()),
                kit, jumpCharges, abilityCharges,
                center == null ? null : new LegacyWorldObservation.BlockPoint(center.getX(), center.getY(), center.getZ()),
                pad == null ? null : new LegacyWorldObservation.Pad(pad.row, pad.column, pad.distanceSq, padReached),
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
        clearRoundState();
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

    /**
     * Reconstruct the maze from the authoritative 1.8 Monster Maze layouts.
     *
     * The server source places every non-zero layout cell at centerY - 1,
     * while zero cells remain air.  This deliberately ignores the visual
     * block palette and active-pad replacement, because both are presentation
     * details and are not part of the logical maze topology.
     */
    private boolean readMaze(World world, BlockPos center, int[][] raw) {
        int pattern = findMatchingPattern(world, center);
        if (pattern < 0) {
            cachedMazePattern = -1;
            return false;
        }
        int[][] expected = MazeLayouts.ALL_MAZES[pattern];
        for (int row = 0; row < MAZE_SIZE; row++) {
            System.arraycopy(expected[row], 0, raw[row], 0, MAZE_SIZE);
        }
        cachedMazePattern = pattern;
        return true;
    }

    private int findMatchingPattern(World world, BlockPos center) {
        for (int pattern = 0; pattern < MazeLayouts.ALL_MAZES.length; pattern++) {
            if (matchesMazeOccupancy(world, center, MazeLayouts.ALL_MAZES[pattern])) return pattern;
        }
        return -1;
    }

    private BlockPos findMazeCenter(World world, EntityPlayerSP player, boolean scoreboardDetected) {
        if (cachedCenter != null && cachedMazeDetected) return cachedCenter;

        int px = player.getPosition().getX();
        int pz = player.getPosition().getZ();
        int py = (int) Math.floor(player.posY);

        // MazeGenerator stores the arena center at the player's feet Y-level and
        // places the walkable surface at centerY - 1. Prioritize that relationship,
        // with nearby fallbacks for teleport/interpolation timing.
        int[] candidateCenterYs = new int[] { py, py - 1, py + 1, py - 2 };

        // The Eye of Ender map is explicitly centred on world X/Z 0,0. The old
        // detector searched only around the player, which failed as soon as the
        // player was more than six blocks from mid (a normal gameplay position).
        for (int centerY : candidateCenterYs) {
            BlockPos knownCenter = new BlockPos(KNOWN_CENTER_X, centerY, KNOWN_CENTER_Z);
            if (matchesCenterAnchor(world, knownCenter)) {
                int pattern = findMatchingPattern(world, knownCenter);
                if (pattern >= 0) {
                    cachedCenter = knownCenter;
                    cachedMazePattern = pattern;
                    return cachedCenter;
                }
            }
        }

        // Fallback for translated/test arenas. This is intentionally broader than
        // the old six-block search, but only runs until a complete source layout
        // match is found and is therefore not part of the per-tick hot path.
        for (int centerY : candidateCenterYs) {
            for (int x = px - CENTER_SEARCH_RADIUS; x <= px + CENTER_SEARCH_RADIUS; x++) {
                for (int z = pz - CENTER_SEARCH_RADIUS; z <= pz + CENTER_SEARCH_RADIUS; z++) {
                    if (x == KNOWN_CENTER_X && z == KNOWN_CENTER_Z) continue;
                    BlockPos candidate = new BlockPos(x, centerY, z);
                    if (!matchesCenterAnchor(world, candidate)) continue;
                    int pattern = findMatchingPattern(world, candidate);
                    if (pattern >= 0) {
                        cachedCenter = candidate;
                        cachedMazePattern = pattern;
                        return cachedCenter;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Source-aware center anchor.
     *
     * MazeGenerator marks the central safe zone with layout values 3-6 and writes
     * those cells as STAINED_CLAY data 5. This is a much stronger anchor than a
     * generic occupancy check and directly models the authoritative server logic.
     */
    private boolean matchesCenterAnchor(World world, BlockPos center) {
        int surfaceY = center.getY() - 1;

        for (int pattern = 0; pattern < MazeLayouts.ALL_MAZES.length; pattern++) {
            int[][] expected = MazeLayouts.ALL_MAZES[pattern];
            boolean possible = true;
            boolean sawCenterMarker = false;

            for (int row = 49 - CENTER_ANCHOR_RADIUS;
                 row <= 49 + CENTER_ANCHOR_RADIUS && possible; row++) {
                for (int col = 49 - CENTER_ANCHOR_RADIUS;
                     col <= 49 + CENTER_ANCHOR_RADIUS; col++) {
                    int x = center.getX() - HALF_MAZE + row;
                    int z = center.getZ() - HALF_MAZE + col;
                    net.minecraft.block.state.IBlockState state =
                            world.getBlockState(new BlockPos(x, surfaceY, z));

                    int value = expected[row][col];
                    if (value >= 3 && value <= 6) {
                        sawCenterMarker = true;
                        if (state.getBlock() != Blocks.stained_hardened_clay
                                || Blocks.stained_hardened_clay.getMetaFromState(state) != 5) {
                            possible = false;
                            break;
                        }
                    } else {
                        boolean expectedOccupied = value != 0;
                        boolean actualOccupied = state.getBlock() != Blocks.air;
                        if (expectedOccupied != actualOccupied) {
                            possible = false;
                            break;
                        }
                    }
                }
            }

            if (possible && sawCenterMarker) return true;
        }
        return false;
    }

    private boolean matchesMazeOccupancy(World world, BlockPos center, int[][] expected) {
        int surfaceY = center.getY() - 1;
        List<BlockPos> activePads = findActivePadCenters(world, center, surfaceY);

        for (int row = 0; row < MAZE_SIZE; row++) {
            for (int col = 0; col < MAZE_SIZE; col++) {
                int x = center.getX() - HALF_MAZE + row;
                int z = center.getZ() - HALF_MAZE + col;

                // SafePad.captureAndBuild replaces a symmetric 5x5 surface with
                // clay/beacon regardless of the underlying layout cell. Ignore that
                // presentation mutation and compare the remaining topology exactly.
                if (withinAnyPad(x, z, activePads)) continue;

                boolean expectedOccupied = expected[row][col] != 0;
                boolean actualOccupied = world.getBlockState(
                        new BlockPos(x, surfaceY, z)).getBlock() != Blocks.air;
                if (expectedOccupied != actualOccupied) return false;
            }
        }
        return true;
    }

    private List<BlockPos> findActivePadCenters(World world, BlockPos center, int surfaceY) {
        List<BlockPos> pads = new ArrayList<BlockPos>();
        for (int x = center.getX() - HALF_MAZE; x <= center.getX() + HALF_MAZE; x++) {
            for (int z = center.getZ() - HALF_MAZE; z <= center.getZ() + HALF_MAZE; z++) {
                if (world.getBlockState(new BlockPos(x, surfaceY, z)).getBlock() == Blocks.beacon) {
                    pads.add(new BlockPos(x, surfaceY, z));
                }
            }
        }
        return pads;
    }

    private boolean withinAnyPad(int x, int z, List<BlockPos> pads) {
        for (BlockPos pad : pads) {
            if (Math.abs(x - pad.getX()) <= SAFE_PAD_RADIUS
                    && Math.abs(z - pad.getZ()) <= SAFE_PAD_RADIUS) {
                return true;
            }
        }
        return false;
    }

    private void clearRoundState() {
        cachedCenter = null;
        cachedPad = null;
        cachedPadCenter = null;
        ticksSinceLastPadRefresh = 0;
        cachedMaze = new int[MAZE_SIZE][MAZE_SIZE];
        cachedMazeDetected = false;
        cachedMazePattern = -1;
        ticksSinceLastMazeRefresh = 0;
        gameStartWorldTick = -1L;
        previouslyInMonsterMaze = false;
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

    static String formatScoreboardLine(String formatted, int scorePoints) {
        return (formatted == null ? "" : formatted) + ": " + scorePoints;
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

            // Minecraft's 1.8 scoreboard renderer displays the score value to the
            // right of each entry, but getPlayerName() only returns the entry text.
            // Monster Maze uses that numeric score for values such as the Safe Pad
            // countdown and Stage. Preserve the rendered "Entry: score" form so the
            // existing pure parser can consume both inline text timers and native
            // scoreboard score values.
            lines.add(formatScoreboardLine(formatted, score.getScorePoints()));
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
        public final PadObservation pad;        public final Minecraft18ObservationRules.ScoreboardData scoreboard;
        public final LegacyWorldObservation state;

        private Observation(boolean inMonsterMaze, boolean mazeDetected, BlockPos center,
                            PadObservation pad,                            Minecraft18ObservationRules.ScoreboardData scoreboard,
                            LegacyWorldObservation state) {
            this.inMonsterMaze = inMonsterMaze;
            this.mazeDetected = mazeDetected;
            this.center = center;
            this.pad = pad;
            this.scoreboard = scoreboard;
            this.state = state;
        }

        public String toLogLine() {
            StringBuilder monsters = new StringBuilder("[");
            for (int i = 0; i < state.monsters.size(); i++) {
                if (i > 0) {
                    monsters.append(";");
                }
                LegacyWorldObservation.Monster monster = state.monsters.get(i);
                monsters.append(String.format(Locale.ROOT,
                        "%d,%s,%s,%.2f,%.2f,%.2f,%.3f,%.3f,%.3f,%s",
                        monster.id, monster.gameplayType, monster.visualType, monster.x, monster.y, monster.z,
                        monster.vx, monster.vy, monster.vz, monster.removed));
            }
            monsters.append("]");

            StringBuilder scoreboardLines = new StringBuilder("[");
            for (int i = 0; i < state.scoreboardLines.size(); i++) {
                if (i > 0) {
                    scoreboardLines.append(";");
                }
                scoreboardLines.append(escape(state.scoreboardLines.get(i)));
            }
            scoreboardLines.append("]");

            return String.format(Locale.ROOT,
                    "OBS worldTick=%d inMaze=%s mazeDetected=%s mazePattern=%d alive=%s completed=%s "
                            + "stage=%d safePadSeconds=%d liveSeconds=%d "
                            + "player=(x=%.3f,y=%.3f,z=%.3f,vx=%.4f,vy=%.4f,vz=%.4f,yaw=%.2f,pitch=%.2f,grounded=%s,hp=%.1f,maxHp=%.1f) "
                            + "kit=%s jumpCharges=%d abilityCharges=%d "
                            + "center=%s pad=%s monsters=%s scoreboardTitle=\"%s\" scoreboardLines=%s mazePathCells=%d",
                    state.worldTick, state.inMonsterMaze, state.mazeDetected, state.mazePattern,
                    state.alive, state.completed, state.stage,
                    state.safePadSeconds, state.liveSeconds,
                    state.player.x, state.player.y, state.player.z,
                    state.player.vx, state.player.vy, state.player.vz,
                    state.player.yaw, state.player.pitch, state.player.grounded,
                    state.player.health, state.player.maxHealth,
                    state.kit, state.jumpCharges, state.abilityCharges,
                    center == null ? "none" : formatPoint(center),
                    pad == null ? "none" : formatPad(pad),
                    monsters, escape(scoreboard.title), scoreboardLines,
                    countPathCells(state.maze));
        }

        public String toMazeLogLine() {
            StringBuilder cells = new StringBuilder(MAZE_SIZE * (MAZE_SIZE + 1));
            for (int row = 0; row < state.maze.length; row++) {
                if (row > 0) {
                    cells.append("/");
                }
                for (int col = 0; col < state.maze[row].length; col++) {
                    cells.append(state.maze[row][col] == 0 ? '0' : '1');
                }
            }
            return String.format(Locale.ROOT,
                    "MAZE worldTick=%d center=%s size=%dx%d cells=%s",
                    state.worldTick,
                    center == null ? "none" : formatPoint(center),
                    MAZE_SIZE, MAZE_SIZE, cells);
        }

        private static String formatPoint(BlockPos point) {
            return String.format(Locale.ROOT, "(x=%d,y=%d,z=%d)",
                    point.getX(), point.getY(), point.getZ());
        }

        private static String formatPad(PadObservation pad) {
            return String.format(Locale.ROOT,
                    "(row=%d,col=%d,distanceSq=%.3f,reached=%s)",
                    pad.row, pad.column, pad.distanceSq,
                    pad.row >= 0 && pad.column >= 0 && pad.distanceSq <= 2.25);
        }

        private static int countPathCells(int[][] maze) {
            int count = 0;
            for (int row = 0; row < maze.length; row++) {
                for (int col = 0; col < maze[row].length; col++) {
                    if (maze[row][col] != 0) {
                        count++;
                    }
                }
            }
            return count;
        }

        private static String escape(String value) {
            return value == null ? "" : value.replace("\\", "\\\\").replace(";", "\\;")
                    .replace("\"", "\\\"");
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
        public String toString() {            return String.format(Locale.ROOT, "(%d,%d)", row, column);
        }
    }
}
