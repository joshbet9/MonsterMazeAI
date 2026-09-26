package me.monstermazeai.adapter;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeCoordinates;
import me.monstermazeai.monster.MonsterState;

/**
 * Builds the AI's simulation/world-model state from a version-neutral live
 * observation. No Minecraft classes are referenced here.
 */
public final class ObservationWorldModel {
    private ObservationWorldModel() {}

    public static GameState from(LegacyWorldObservation observation) {
        if (observation == null) throw new IllegalArgumentException("observation");

        GameState state = new GameState();
        state.tick = observation.worldTick;
        state.inMonsterMaze = observation.inMonsterMaze && observation.mazeDetected;
        state.stage = observation.stage;
        state.mazePattern = observation.mazePattern;
        state.phaseTicksRemaining = observation.safePadSeconds < 0 ? -1 : observation.safePadSeconds * 20;
        state.liveSeconds = observation.liveSeconds;
        state.alive = observation.alive;
        state.completed = observation.completed;
        state.kit = observation.kit;
        state.ability.charges = observation.abilityCharges;
        if (!state.inMonsterMaze) {
            return state;
        }
        if (observation.center == null) {
            throw new IllegalArgumentException("Detected Monster Maze observation requires center");
        }
        state.player.y = observation.player.y - observation.center.y;

        MazeCoordinates coordinates = new MazeCoordinates(observation.center);
        Cell playerCell = coordinates.containingCell(observation.player.x, observation.player.z);
        state.player.x = coordinates.logicalX(observation.player.x);
        state.player.z = coordinates.logicalZ(observation.player.z);

        state.player.vx = observation.player.vx;
        state.player.vy = observation.player.vy;
        state.player.vz = observation.player.vz;
        state.player.yaw = observation.player.yaw;
        state.player.pitch = observation.player.pitch;
        state.player.grounded = observation.player.grounded;
        state.player.health = observation.player.health;
        state.player.maxHealth = observation.player.maxHealth;
        state.player.jumpCharges = observation.jumpCharges;

        state.maze = new me.monstermazeai.maze.MazeModel(observation.maze);
        for (int row = 0; row < me.monstermazeai.maze.MazeModel.SIZE; row++) {
            for (int column = 0; column < me.monstermazeai.maze.MazeModel.SIZE; column++) {
                state.maze.setPhysicalFloor(row, column, observation.physicalFloor[row][column]);
                // Source Monster Maze disables centre-safe-zone path cells for
                // monster movement until the final deterioration pass.
                int value = observation.maze[row][column];
                if (value == 5 || value == 6) {
                    state.maze.setDisabled(row, column, true);
                }
            }
        }

        // On the source's final centre-deterioration pass, the non-path centre
        // cells (3/4) disappear while path cells (5/6) are rebuilt as normal
        // maze blocks and re-enter the monster waypoint graph. Because all
        // centre cells are changed in that same pass, a single missing 3/4
        // physical cell is an unambiguous completion signal.
        boolean centreDeteriorated = false;
        for (int row = 0; row < me.monstermazeai.maze.MazeModel.SIZE && !centreDeteriorated; row++) {
            for (int column = 0; column < me.monstermazeai.maze.MazeModel.SIZE; column++) {
                int value = observation.maze[row][column];
                if ((value == 3 || value == 4) && !observation.physicalFloor[row][column]) {
                    centreDeteriorated = true;
                    break;
                }
            }
        }
        if (centreDeteriorated) {
            for (int row = 0; row < me.monstermazeai.maze.MazeModel.SIZE; row++) {
                for (int column = 0; column < me.monstermazeai.maze.MazeModel.SIZE; column++) {
                    int value = observation.maze[row][column];
                    if ((value == 5 || value == 6) && observation.physicalFloor[row][column]) {
                        state.maze.setDisabled(row, column, false);
                    }
                }
            }
        }

        if (observation.pad != null && observation.pad.row >= 0 && observation.pad.column >= 0) {
            // Keep the authoritative raw layout intact for physical player routing.
            // The active Safe Pad is temporarily removed only from the logical
            // monster topology, not from the physical floor graph.
            for (int row = observation.pad.row - 2; row <= observation.pad.row + 2; row++) {
                for (int column = observation.pad.column - 2; column <= observation.pad.column + 2; column++) {
                    if (row >= 0 && row < me.monstermazeai.maze.MazeModel.SIZE
                            && column >= 0 && column < me.monstermazeai.maze.MazeModel.SIZE) {
                        state.maze.setDisabled(row, column, true);
                        // SafePad.build creates a physical 5x5 surface even when
                        // the canonical maze cell underneath was not a path cell.
                        state.maze.setPhysicalFloor(row, column, true);
                    }
                }
            }
        }

        if (observation.pad != null && observation.pad.row >= 0 && observation.pad.column >= 0) {
            state.activePadRow = observation.pad.row;
            state.activePadColumn = observation.pad.column;
            state.padReached = observation.pad.reached;
        }

        for (LegacyWorldObservation.Monster observed : observation.monsters) {
            MonsterState monster = new MonsterState(
                    observed.id,
                    coordinates.logicalX(observed.x),
                    observed.y - observation.center.y,
                    coordinates.logicalZ(observed.z));
            monster.vx = observed.vx;
            monster.vy = observed.vy;
            monster.vz = observed.vz;
            monster.removed = observed.removed;
            {
                Cell cell = coordinates.containingCell(observed.x, observed.z);
                if (coordinates.inBounds(cell)) {
                    monster.waypointRow = cell.row();
                    monster.waypointColumn = cell.column();
                }
            }
            state.monsters.add(monster);
        }

        return state;
    }

}
