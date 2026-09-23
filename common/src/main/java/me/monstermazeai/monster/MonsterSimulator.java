package me.monstermazeai.monster;

import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;

import java.util.List;
import java.util.Random;

public final class MonsterSimulator {
    private static final double WAYPOINT_TOLERANCE = 0.4;

    private final MazeModel maze;
    private final Random random;
    private final double speed;

    public MonsterSimulator(MazeModel maze, Random random, double speed) {
        this.maze = maze;
        this.random = random;
        this.speed = speed;
    }

    /** Implements the Monster Maze waypoint decision rule; movement integration is separate. */
    public void chooseNextWaypoint(MonsterState monster, Cell currentCell) {
        List<Cell> choices = maze.cardinalNeighbours(currentCell);
        if (choices.size() > 1 && monster.direction != CardinalDirection.NONE) {
            choices.removeIf(c -> {
                int dr = c.row() - currentCell.row();
                int dc = c.column() - currentCell.column();
                return CardinalDirection.between(dr, dc) == monster.direction.opposite();
            });
        }
        if (choices.isEmpty()) return;
        Cell chosen = choices.get(random.nextInt(choices.size()));
        monster.waypointRow = chosen.row();
        monster.waypointColumn = chosen.column();
        monster.direction = CardinalDirection.between(
                chosen.row() - currentCell.row(), chosen.column() - currentCell.column());
    }

    public void tick(me.monstermazeai.game.GameState state) {
        for (MonsterState m : state.monsters) {
            if (m.frozen() || m.launched()) continue;
            Cell current = nearestCell(m.x, m.z);
            if (current == null) continue;
            if (m.waypointRow < 0 || atWaypoint(m, centerX(m.waypointRow), centerZ(m.waypointColumn))) {
                chooseNextWaypoint(m, current);
            }
            if (m.waypointRow < 0) continue;
            double tx=centerX(m.waypointRow), tz=centerZ(m.waypointColumn);
            double dx=tx-m.x, dz=tz-m.z, d=Math.hypot(dx,dz);
            if (d > 1e-9) {
                double step=Math.min(speed,d);
                m.vx=dx/d*step; m.vz=dz/d*step;
                m.x += m.vx; m.z += m.vz;
            }
        }
    }

    private Cell nearestCell(double x, double z) {
        int r=(int)Math.floor(x+0.5), c=(int)Math.floor(z+0.5);
        if(r<0 || c<0 || r>=MazeModel.SIZE || c>=MazeModel.SIZE) return null;
        return maze.isRawPath(r,c) ? new Cell(r,c) : null;
    }

    private double centerX(int row) { return row; }
    private double centerZ(int column) { return column; }

    public boolean atWaypoint(MonsterState m, double targetX, double targetZ) {
        double dx = m.x - targetX, dz = m.z - targetZ;
        return Math.sqrt(dx * dx + dz * dz) < WAYPOINT_TOLERANCE;
    }

    public double speed() { return speed; }
}