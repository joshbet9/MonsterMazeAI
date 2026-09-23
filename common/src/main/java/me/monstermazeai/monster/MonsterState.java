package me.monstermazeai.monster;

public final class MonsterState {
    public final int id;
    public double x, y, z;
    public double vx, vy, vz;
    public int waypointRow = -1, waypointColumn = -1;
    public CardinalDirection direction = CardinalDirection.NONE;
    public long frozenUntilTick;
    public long launchedUntilTick;
    public long launchedAtTick;
    public boolean removed;

    public MonsterState(int id, double x, double y, double z) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean frozen(long tick) { return frozenUntilTick > tick; }
    public boolean launched(long tick) { return launchedUntilTick > tick; }

    public MonsterState copy() {
        MonsterState m = new MonsterState(id, x, y, z);
        m.vx=vx; m.vy=vy; m.vz=vz;
        m.waypointRow=waypointRow; m.waypointColumn=waypointColumn;
        m.direction=direction;
        m.frozenUntilTick=frozenUntilTick;
        m.launchedUntilTick=launchedUntilTick;
        m.launchedAtTick=launchedAtTick;
        m.removed=removed;
        return m;
    }
}
