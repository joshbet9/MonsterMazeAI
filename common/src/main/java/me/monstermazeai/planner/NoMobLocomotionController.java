         * every tick; only search alternatives when the chosen target-centre
         * vector would leave the physical floor on the very next source tick.
         */
        Action edgeAligned = driveVector(
                state, edge.dirX, edge.dirZ, 1.0, true, false);
        if (projectedFloorSafe(state, edgeAligned)) {
            lastDecision += "_FALLBACK_EDGE";
            return edgeAligned;
        }

        if (Math.abs(crossTrack) > 0.02D) {
            double correction = Math.max(
                    -0.35D, Math.min(0.35D, -crossTrack * 1.5D));
            double correctedX = edge.dirX;
            double correctedZ = edge.dirZ;
            if (edge.dirX != 0.0D) correctedZ += correction;
            else correctedX += correction;

            double len = Math.hypot(correctedX, correctedZ);
            if (len > 1.0E-9D) {
                correctedX /= len;
                correctedZ /= len;
                Action lane = driveVector(
                        state, correctedX, correctedZ, 1.0, true, false);
                if (projectedFloorSafe(state, lane)) {
                    lastDecision += "_FALLBACK_LANE";
                    return lane;
                }
            }
        }

        Action brake = brakeVelocity(state);
        if (projectedFloorSafe(state, brake)) {
            lastDecision += "_FALLBACK_BRAKE";
            return brake;
        }

        /*
         * A full-strength counter-input can itself overshoot a narrow corner.
         * Before giving up and idling, search the same source-valid input at
         * smaller magnitudes. This is still ordinary WASD/braking; it simply
         * preserves the player's existing momentum instead of surrendering
         * control on the one tick where an edge is most sensitive.
         */
        double[] scales = {0.75D, 0.50D, 0.25D};
        for (double scale : scales) {
            Action scaledEdge = scaleMovement(edgeAligned, scale);
            if (projectedFloorSafe(state, scaledEdge)) {
                lastDecision += "_FALLBACK_SCALED_EDGE_" + format(scale);
                return scaledEdge;
            }
            if (Math.abs(crossTrack) > 0.02D) {
                Action scaledBrake = scaleMovement(brake, scale);
                if (projectedFloorSafe(state, scaledBrake)) {
                    lastDecision += "_FALLBACK_SCALED_BRAKE_" + format(scale);
                    return scaledBrake;
                }
            }
        }

        lastDecision += "_FALLBACK_IDLE";
        return Action.IDLE;

    }

    private static Action scaleMovement(Action action, double scale) {
        if (action == null) return Action.IDLE;
        double bounded = Math.max(0.0D, Math.min(1.0D, scale));
        return new Action(
                action.forward() * bounded,
                action.strafe() * bounded,
                action.jump(),
                action.sprint(),
                action.yawDelta(),
                action.useAbility());
    }
    }

    private boolean projectedFloorSafe(GameState state, Action action) {
        me.monstermazeai.player.PlayerState projected = state.player.copy();
        int jumpAmplifier =
                state.kit == Kit.JUMPER && state.ability.charges > 0 ? 0 : -10;

        new LegacyMazePhysics().tick(
                projected, action, state.maze, jumpAmplifier);

        if (projected.y < -0.01D) return false;

        boolean airborneJump =
                action.jump()
                        && state.kit == Kit.JUMPER
                        && state.ability.charges > 0
                        && projected.y > 0.01D;
        return airborneJump
                || physicalFloorUnderAabb(state, projected.x, projected.z);
    }

    private static boolean physicalFloorUnderAabb(
            GameState state, double x, double z) {
        final double halfWidth = 0.30D;
        int minRow = (int) Math.floor(x - halfWidth);
        int maxRow = (int) Math.floor(Math.nextDown(x + halfWidth));
        int minColumn = (int) Math.floor(z - halfWidth);
        int maxColumn = (int) Math.floor(Math.nextDown(z + halfWidth));

        for (int row = minRow; row <= maxRow; row++) {
            for (int column = minColumn; column <= maxColumn; column++) {
                if (state.maze.isPhysicalFloor(row, column)) return true;
            }
        }
        return false;
    }

    private Action brakeVelocity(GameState state) {
        double speed = Math.hypot(state.player.vx, state.player.vz);
        if (speed < 1.0E-9D) {
            return Action.IDLE;
        }