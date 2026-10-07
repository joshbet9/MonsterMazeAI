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
    // MonsterMaze source stores an arbitrary physical arena centre. The maze
    // array is mapped relative to that centre (center - 49 + array index), so
    // world 0,0 is not authoritative and must never be hard-coded.
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
    private boolean[][] cachedPhysicalFloor;
    private BlockPos cachedPhysicalPad;

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
        } else if (cachedPadCenter == null || !cachedPadCenter.equals(center) || cachedPad == null || !cachedPadBeaconExists(world, center)) {
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
        boolean[][] physicalFloor = buildPhysicalFloor(world, center, raw, pad);

        boolean inMonsterMaze = !player.isSpectator() && (mazeScoreboard || mazeDetected || pad != null);
        if (inMonsterMaze && !previouslyInMonsterMaze) {
            gameStartWorldTick = world.getTotalWorldTime();
        } else if (!inMonsterMaze) {
            gameStartWorldTick = -1L;
        }
        previouslyInMonsterMaze = inMonsterMaze;

        long worldTick = world.getTotalWorldTime();
        int stage = Math.max(1, scoreboard.stage);
        // Preserve -1 as "timer unavailable". Zero is a real source state at the
        // instant the phase expires; collapsing -1 to zero made the common
        // controller treat an otherwise valid live objective as expired.
        int safePadSeconds = scoreboard.safePadSeconds;
        int liveSeconds = gameStartWorldTick < 0
                ? 0
                : (int) Math.max(0, (worldTick - gameStartWorldTick) / 20L);
        boolean alive = player.getHealth() > 0.0F && !player.isSpectator();
        boolean completed = scoreboard.completed || player.isSpectator();
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
                scoreboard.mode, alive, completed, stage, safePadSeconds, liveSeconds,
                new LegacyWorldObservation.Player(
                        player.posX, player.posY, player.posZ,
                        player.motionX, player.motionY, player.motionZ,
                        player.rotationYaw, player.rotationPitch, player.onGround,
                        player.getHealth(), player.getMaxHealth()),
                kit, jumpCharges, abilityCharges,
                center == null ? null : new LegacyWorldObservation.BlockPoint(center.getX(), center.getY(), center.getZ()),
                pad == null ? null : new LegacyWorldObservation.Pad(pad.row, pad.column, pad.distanceSq, padReached),
                raw, physicalFloor, monsters, scoreboard.title, scoreboard.lines);

        return new Observation(
                matchedMaze,
                mazeDetected,
                center,
                pad,
                scoreboard,
                observation
        );
    }

    /**
     * Build the player's currently usable floor from the live centre state.
     *
     * The source keeps every centre-safe-zone block physically present through
     * the first nine deterioration passes. Only on the final pass are the
     * non-path centre cells removed; centre path cells are rebuilt into normal
     * maze blocks and become monster waypoints again. This is identical for
     * Maze 1, Maze 2 and Maze 3; only the source layout determines which cells
     * are centre-safe-zone cells.
     */
    private boolean[][] buildPhysicalFloor(World world, BlockPos center, int[][] raw, PadObservation activePad) {
        if (cachedPhysicalFloor == null || center == null || !cachedMazeDetected) {
            boolean[][] floor = new boolean[MAZE_SIZE][MAZE_SIZE];
            for (int row = 0; row < MAZE_SIZE; row++) {
                for (int col = 0; col < MAZE_SIZE; col++) floor[row][col] = raw[row][col] != 0;
            }
            cachedPhysicalFloor = floor;
            cachedPhysicalPad = null;
        }
        boolean[][] floor = cachedPhysicalFloor;
        if (center == null || !cachedMazeDetected) return floor;

        int surfaceY = center.getY() - 1;
        for (int row = 49 - CENTER_ANCHOR_RADIUS; row <= 49 + CENTER_ANCHOR_RADIUS; row++) {
            for (int col = 49 - CENTER_ANCHOR_RADIUS; col <= 49 + CENTER_ANCHOR_RADIUS; col++) {
                int value = raw[row][col];
                if (value < 3 || value > 6) continue;
                int x = center.getX() - HALF_MAZE + row;
                int z = center.getZ() - HALF_MAZE + col;
                floor[row][col] = world.getBlockState(new BlockPos(x, surfaceY, z)).getBlock() != Blocks.air;
            }
        }

        // The source's once-per-second task starts at 20s with decay=10 and
        // reaches its final decay=1 pass at ~29s. Keep the live block reading
        // authoritative, but expose the source lifecycle explicitly for the
        // common model so centre path cells can be re-enabled for monsters only
        // after deterioration has completed.
        if (activePad != null && activePad.row >= 0 && activePad.column >= 0) {
            int cx = center.getX() - HALF_MAZE + activePad.row;
            int cz = center.getZ() - HALF_MAZE + activePad.column;
            if (cachedPhysicalPad == null || cachedPhysicalPad.getX() != cx || cachedPhysicalPad.getZ() != cz) {
                if (cachedPhysicalPad != null) restoreRawPadArea(raw, center, floor, cachedPhysicalPad);
                markPhysicalPadArea(center, floor, cx, cz);
                cachedPhysicalPad = new BlockPos(cx, center.getY(), cz);
            }
        }
        return floor;
    }

    private static void restoreRawPadArea(int[][] raw, BlockPos center, boolean[][] floor, BlockPos pad) {
        int baseRow = pad.getX() - (center.getX() - HALF_MAZE), baseCol = pad.getZ() - (center.getZ() - HALF_MAZE);
        for (int dr = -SAFE_PAD_RADIUS; dr <= SAFE_PAD_RADIUS; dr++) for (int dc = -SAFE_PAD_RADIUS; dc <= SAFE_PAD_RADIUS; dc++) {
            int row = baseRow + dr, col = baseCol + dc;
            if (row >= 0 && row < MAZE_SIZE && col >= 0 && col < MAZE_SIZE) floor[row][col] = raw[row][col] != 0;
        }
    }

    private static void markPhysicalPadArea(BlockPos center, boolean[][] floor, int cx, int cz) {
        int baseRow = cx - (center.getX() - HALF_MAZE), baseCol = cz - (center.getZ() - HALF_MAZE);
        for (int dr = -SAFE_PAD_RADIUS; dr <= SAFE_PAD_RADIUS; dr++) for (int dc = -SAFE_PAD_RADIUS; dc <= SAFE_PAD_RADIUS; dc++) {
            int row = baseRow + dr, col = baseCol + dc;
            if (row >= 0 && row < MAZE_SIZE && col >= 0 && col < MAZE_SIZE) floor[row][col] = true;
        }
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
        if (center == null || pad.row < 0 || pad.column < 0) {
            return false;
        }

        // Mirror SafePad.isOn() from the source plugin exactly. SafePad is
        // constructed at (nextX, centerY-1, nextZ) and accepts the player
        // anywhere inside its 5x5 surface bounding box, not a 1.5-block radius.
        double baseX = center.getX() - HALF_MAZE + pad.row;
        double baseY = center.getY() - 1.0D;
        double baseZ = center.getZ() - HALF_MAZE + pad.column;

        // Exact SafePad.isOn() semantics from the source plugin:
        // dx > -2.5, dx < 2.5, dz > -2.5, dz < 2.5, y > padY, y < padY+5.
        // SafePad is constructed from MazeGenerator's path location,
        // which is the centre of the block: world block coordinate + 0.5.
        // Using the integer block corner here makes the reported 5x5 pad
        // one half-block too far toward negative X/Z and can falsely report
        // a player as reached while they are visibly just outside the pad.
        double dx = player.posX - (baseX + 0.5D);
        double dz = player.posZ - (baseZ + 0.5D);
        return dx > -2.5D && dx < 2.5D
                && player.posY > baseY
                && player.posY < baseY + 5.0D
                && dz > -2.5D && dz < 2.5D;
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
        int pattern = cachedMazePattern;
        if (pattern < 0 || pattern >= MazeLayouts.ALL_MAZES.length) {
            pattern = findCenterPattern(world, center);
        }
        if (pattern < 0) {
            cachedMazePattern = -1;
            return false;
        }
        /*
         * The embedded layouts are the authoritative topology. The client only
         * needs the center anchor to identify which source layout is active.
         */
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
        if (cachedCenter != null && cachedMazeDetected) {
            /*
             * Once a complete authoritative maze layout has been identified,
             * the centre is stable for the entire round. Do not revalidate the
             * centre marker every tick: Monster Maze deliberately mutates the
             * centre safe zone during deterioration, so the marker can cease
             * matching even though the player is still inside the same 99x99
             * arena. Losing the centre here destroys the world-to-maze
             * coordinate frame and can stop an otherwise valid run.
             *
             * Y remains a useful sanity check because a teleport/death to a
             * different vertical layer is a genuine round boundary signal.
             */
            /*
             * Do not discard the round coordinate frame for ordinary jump or
             * mob-knockback height changes. The server's centre is fixed for
             * the live round, and centre deterioration changes its blocks after
             * ~20s. A previous 3-block threshold caused a player briefly at
             * Y=69 to invalidate the cache; rediscovery then failed because
             * the centre-safe-zone had already deteriorated.
             *
             * Only a very large vertical displacement is treated as evidence
             * that the player has actually left this arena. The normal fall
             * / elimination path is handled by the server and by the live
             * player state; keeping the coordinate frame here lets the AI
             * recover from temporary vertical knockback without re-matching
             * destroyed centre geometry.
             */
            int playerY = player.getPosition().getY();
            if (Math.abs(playerY - cachedCenter.getY()) <= 20) {
                return cachedCenter;
            }
            System.out.println("[MonsterMazeAI/1.8] CENTER CACHE INVALID old="
                    + cachedCenter.getX() + "," + cachedCenter.getY() + "," + cachedCenter.getZ()
                    + " player=" + player.posX + "," + player.posY + "," + player.posZ
                    + " reason=vertical-mismatch");
            cachedCenter = null;
            cachedMazeDetected = false;
            cachedMazePattern = -1;
            cachedMaze = new int[MAZE_SIZE][MAZE_SIZE];
            cachedPhysicalFloor = null;
            cachedPhysicalPad = null;
            cachedPad = null;
            cachedPadCenter = null;
        }

        int px = player.getPosition().getX();
        int pz = player.getPosition().getZ();
        int py = (int) Math.floor(player.posY);

        // MazeGenerator stores the arena center at the player's feet Y-level and
        // places the walkable surface at centerY - 1. Prioritize that relationship,
        // with nearby fallbacks for teleport/interpolation timing.
        int[] candidateCenterYs = new int[] { py, py - 1, py + 1, py - 2 };

        // MonsterMaze's source centre is configured by the server and may be
        // translated. Search around the player for the authoritative centre marker
        // instead of assuming world origin. This only runs until a complete source
        // layout match is found and is not part of the per-tick hot path.
        for (int centerY : candidateCenterYs) {
            for (int x = px - CENTER_SEARCH_RADIUS; x <= px + CENTER_SEARCH_RADIUS; x++) {
                for (int z = pz - CENTER_SEARCH_RADIUS; z <= pz + CENTER_SEARCH_RADIUS; z++) {
                    BlockPos candidate = new BlockPos(x, centerY, z);
                    int pattern = findCenterPattern(world, candidate);
                    if (pattern >= 0) {
                        cachedCenter = candidate;
                        cachedMazePattern = pattern;
                        /*
                         * The center anchor is a source-accurate, pattern-specific
                         * 13x13 signature. Requiring the full 99x99 occupancy match
                         * made startup depend on every arena block being loaded in
                         * the client at the same moment.
                         */
                        cachedMazeDetected = true;
                        cachedMaze = new int[MAZE_SIZE][MAZE_SIZE];
                        for (int row = 0; row < MAZE_SIZE; row++) {
                            System.arraycopy(
                                    MazeLayouts.ALL_MAZES[pattern][row],
                                    0, cachedMaze[row], 0, MAZE_SIZE);
                        }
                        cachedPhysicalFloor = null;
                        cachedPhysicalPad = null;
                        System.out.println("[MonsterMazeAI/1.8] CENTER DETECTED center="
                                + candidate.getX() + "," + candidate.getY() + "," + candidate.getZ()
                                + " pattern=" + (pattern + 1)
                                + " player=" + player.posX + "," + player.posY + "," + player.posZ);
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
    private int findCenterPattern(World world, BlockPos center) {
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

            if (possible && sawCenterMarker) return pattern;
        }
        return -1;
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
        cachedPhysicalFloor = null;
        cachedPhysicalPad = null;
        cachedMazeDetected = false;
        cachedMazePattern = -1;
        ticksSinceLastMazeRefresh = 0;
        gameStartWorldTick = -1L;
        previouslyInMonsterMaze = false;
    }

    private boolean cachedPadBeaconExists(World world, BlockPos center) {
        if (cachedPad == null || cachedPad.row < 0 || cachedPad.column < 0) return false;
        int x = center.getX() - HALF_MAZE + cachedPad.row, z = center.getZ() - HALF_MAZE + cachedPad.column;
        return world.getBlockState(new BlockPos(x, center.getY() - 1, z)).getBlock() == net.minecraft.init.Blocks.beacon;
    }

    private PadObservation findActivePad(World world, EntityPlayerSP player, BlockPos center) {
        // Source behaviour: when the phase timer reaches 2 seconds the server
        // builds _nextSafePad while leaving _safePad active. That creates two
        // beacons briefly. The active objective is still _safePad until the
        // phase reaches zero, so never switch to the nearest beacon merely
        // because the preview pad was built.
        if (cachedPad != null && cachedPad.row >= 0 && cachedPad.column >= 0) {
            int cachedX = center.getX() - HALF_MAZE + cachedPad.row;
            int cachedZ = center.getZ() - HALF_MAZE + cachedPad.column;
            BlockPos cachedBeacon = new BlockPos(cachedX, center.getY() - 1, cachedZ);
            if (world.getBlockState(cachedBeacon).getBlock() == net.minecraft.init.Blocks.beacon) {
                return new PadObservation(
                        cachedPad.row,
                        cachedPad.column,
                        player.getDistanceSq(cachedX + 0.5, center.getY(), cachedZ + 0.5));
            }
        }

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

    static String formatScoreboardLine(String formatted) {
        return formatted == null ? "" : formatted;
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

            // Mineplex does NOT store the visible line value in Score.getScorePoints().
            // ScoreboardElement uses the score points only as the line's ordering
            // number and stores the actual visible text in the Team prefix/suffix.
            // Reconstruct that rendered line exactly; the pure parser then sees
            // "Safe Pad", "60 Seconds", "Stage", "1", etc.
            lines.add(formatScoreboardLine(formatted));
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
