package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Monster Maze's flat 1.8 player physics.
 *
 * No wall collision or step-up simulation is applied here. The maze is a
 * flat floating platform; non-path cells represent absence/containment rather
 * than ordinary traversable walls for the player physics model.
 */
public final class LegacyMazePhysics implements PhysicsModel {
    private final LegacyMovementModel movement = new LegacyMovementModel();

    /** Kept for source compatibility with the earlier prototype. */
    public LegacyMazePhysics(MazeCollision ignoredCollision) {
    }

    public LegacyMazePhysics() {
    }

    @Override
    public void tick(PlayerState p, Action action) {
        movement.tick(p, action);
    }
}
