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

        if (pad != null && pad.row >= 0 && mazeDetected) {
            disablePadArea(raw, pad.row, pad.column);
        }

        List<LegacyWorldObservation.Monster> monsters = new ArrayList<LegacyWorldObservation.Monster>();
        for (Entity entity : world.loadedEntityList) {
            if (!isMonsterMazeMob(entity)) {
                continue;
            }
            monsters.add(new LegacyWorldObservation.Monster(
                    entity.getEntityId(), entity.getClass().getSimpleName(),
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
            if (matchesMazeOccupancy(world, center, MazeLayouts.ALL_MAZES[pattern])) {
                return pattern;
            }
        }
        return -1;
    }

    private boolean matchesMazeOccupancy(World world, BlockPos center, int[][] expected) {
        int mismatches = 0;
        for (int row = 0; row < MAZE_SIZE; row++) {
            for (int col = 0; col < MAZE_SIZE; col++) {
                int x = center.getX() - HALF_MAZE + row;
                int z = center.getZ() - HALF_MAZE + col;
                boolean expectedOccupied = expected[row][col] != 0;