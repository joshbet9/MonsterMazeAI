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
        state.phaseTicksRemaining = Math.max(0, observation.safePadSeconds) * 20;
        state.liveSeconds = observation.liveSeconds;
        state.alive = observation.alive;
        state.completed = observation.completed;
        state.kit = observation.kit;
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
