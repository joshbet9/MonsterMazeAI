package me.monstermazeai.physics;

import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerState;

/**
 * Deterministic baseline movement model. Version adapters replace this with
 * measured Minecraft-specific physics once integrated with the client.
 */
public abstract class AbstractArcadePhysics implements PhysicsModel {
    protected final double horizontalAcceleration;
    protected final double friction;
    protected final double maxSpeed;
    protected final double gravity;
    protected final double jumpVelocity;

    protected AbstractArcadePhysics(double horizontalAcceleration, double friction,
                                    double maxSpeed, double gravity, double jumpVelocity) {
        this.horizontalAcceleration=horizontalAcceleration;
        this.friction=friction;
        this.maxSpeed=maxSpeed;
        this.gravity=gravity;
        this.jumpVelocity=jumpVelocity;
    }

    @Override
    public void tick(PlayerState p, Action a) {
        double ax=a.strafe(), az=a.forward();
        double len=Math.hypot(ax,az);
        if(len>1){ax/=len; az/=len;}
        p.vx += ax*horizontalAcceleration;
        p.vz += az*horizontalAcceleration;
        double speed=Math.hypot(p.vx,p.vz);
        if(speed>maxSpeed){double scale=maxSpeed/speed;p.vx*=scale;p.vz*=scale;}
        if(a.jump() && p.grounded){p.vy=jumpVelocity;p.grounded=false;}
        p.x += p.vx; p.z += p.vz;
        if(!p.grounded){p.vy-=gravity;p.y+=p.vy;if(p.y<=0){p.y=0;p.vy=0;p.grounded=true;}}
        else {p.y=0;}
        if(!a.jump()){p.vx*=friction;p.vz*=friction;}
    }
}