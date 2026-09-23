package me.monstermazeai.monster;

public final class MonsterState {
    public final int id;
    public double x, y, z;
    public double vx, vy, vz;
    public int waypointRow = -1, waypointColumn = -1;
    public CardinalDirection direction = CardinalDirection.NONE;
    public long frozenUntilTick;
    public long launchedUntilTick;

    public MonsterState(int id, double x, double y, double z) {
        this.id = id;
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public boolean frozen(long tick) { return frozenUntilTick > tick; }
    public boolean launched(long tick) { return launchedUntilTick > tick; }
}