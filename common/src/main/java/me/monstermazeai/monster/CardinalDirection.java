package me.monstermazeai.monster;

public enum CardinalDirection {
    NORTH(-1, 0), SOUTH(1, 0), EAST(0, 1), WEST(0, -1), NONE(0, 0);

    public final int dr;
    public final int dc;

    CardinalDirection(int dr, int dc) {
        this.dr = dr;
        this.dc = dc;
    }

    public static CardinalDirection between(int dr, int dc) {
        for (CardinalDirection d : values()) if (d.dr == dr && d.dc == dc) return d;
        return NONE;
    }

    public CardinalDirection opposite() {
        return switch (this) {
            case NORTH -> SOUTH;
            case SOUTH -> NORTH;
            case EAST -> WEST;
            case WEST -> EAST;
            default -> NONE;
        };
    }
}