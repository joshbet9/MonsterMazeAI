package me.monstermazeai.player;

public final class PlayerState {
    public double x, y, z;
    public double vx, vy, vz;
    public float yaw, pitch;
    public boolean grounded;
    public double health = 20.0;
    public double maxHealth = 20.0;
    public int jumpCharges;
    public long nextJumpChargeTick;
    public long recentMobHitUntilTick;

    public PlayerState copy() {
        PlayerState p = new PlayerState();
        p.x=x; p.y=y; p.z=z; p.vx=vx; p.vy=vy; p.vz=vz;
        p.yaw=yaw; p.pitch=pitch; p.grounded=grounded;
        p.health=health; p.maxHealth=maxHealth;
        p.jumpCharges=jumpCharges; p.nextJumpChargeTick=nextJumpChargeTick;
        p.recentMobHitUntilTick=recentMobHitUntilTick;
        return p;
    }
}