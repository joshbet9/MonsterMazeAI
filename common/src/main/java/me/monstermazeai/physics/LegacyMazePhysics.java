package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * 1.8 movement plus Monster Maze block collision.
 * The client adapter must keep PlayerState coordinates in maze-local space.
 */
public final class LegacyMazePhysics implements PhysicsModel {
    private final LegacyMovementModel movement = new LegacyMovementModel();
    private final MazeCollision collision;

    public LegacyMazePhysics(MazeCollision collision){this.collision=collision;}

    @Override
    public void tick(PlayerState p, Action action) {
        // Reproduce the movement acceleration/jump update, but resolve the
        // resulting displacement through the actual maze block geometry.
        double oldX=p.x, oldY=p.y, oldZ=p.z;
        movement.tick(p, action);
        double dx=p.x-oldX, dy=p.y-oldY, dz=p.z-oldZ;
        p.x=oldX; p.y=oldY; p.z=oldZ;
        collision.move(p,dx,dy,dz);
    }
}
