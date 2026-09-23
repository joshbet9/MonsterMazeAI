package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MazeModel;

import java.util.List;
import java.util.Random;

public final class MonsterSimulator {
    private static final double WAYPOINT_TOLERANCE=0.4;
    private static final double CELL_CENTER_OFFSET=0.5;
    private static final double GRAVITY=0.08;
    private static final double AIR_DRAG=0.98;

    private final MazeModel maze;
    private final Random random;
    private final double speed;
    private final long seed;

    public MonsterSimulator(MazeModel maze, Random random, double speed){
        this.maze=maze; this.random=random; this.speed=speed; this.seed=random.nextLong();
    }
    private MonsterSimulator(MazeModel maze,long seed,double speed){
        this.maze=maze; this.random=new Random(seed); this.speed=speed; this.seed=seed;
    }

    public void chooseNextWaypoint(MonsterState monster,Cell currentCell){
        List<Cell> choices=maze.cardinalNeighbours(currentCell);
        if(choices.size()>1 && monster.direction!=CardinalDirection.NONE){
            choices.removeIf(c->{
                int dr=c.row()-currentCell.row(),dc=c.column()-currentCell.column();
                return CardinalDirection.between(dr,dc)==monster.direction.opposite();
            });
        }
        if(choices.isEmpty()){monster.waypointRow=-1;monster.waypointColumn=-1;monster.direction=CardinalDirection.NONE;return;}
        Cell chosen=choices.get(random.nextInt(choices.size()));
        CardinalDirection direction=CardinalDirection.between(chosen.row()-currentCell.row(),chosen.column()-currentCell.column());
        Cell terminal=chosen,cursor=chosen;
        while(true){
            Cell cursorCell=cursor;
            List<Cell> forward=maze.cardinalNeighbours(cursorCell);
            forward.removeIf(c->CardinalDirection.between(c.row()-cursorCell.row(),c.column()-cursorCell.column())!=direction);
            if(forward.isEmpty()) break;
            Cell next=forward.get(0);
            List<Cell> atNext=maze.cardinalNeighbours(next);
            int alternatives=0;
            for(Cell n:atNext){
                CardinalDirection d=CardinalDirection.between(n.row()-next.row(),n.column()-next.column());
                if(d!=direction) alternatives++;
            }
            if(alternatives>1){terminal=next;break;}
            terminal=next;cursor=next;
        }
        monster.waypointRow=terminal.row(); monster.waypointColumn=terminal.column(); monster.direction=direction;
    }

    public void tick(GameState state){
        for(MonsterState m:state.monsters){
            if(m.removed) continue;
            if(m.launched(state.tick)){tickLaunched(state,m);continue;}
            if(m.frozen(state.tick)) continue;
            Cell current=nearestCell(m.x,m.z);
            if(current==null) continue;
            if(m.waypointRow<0 || atWaypoint(m,centerX(m.waypointRow),centerZ(m.waypointColumn))) chooseNextWaypoint(m,current);
            if(m.waypointRow<0) continue;
            double tx=centerX(m.waypointRow),tz=centerZ(m.waypointColumn);
            double dx=tx-m.x,dz=tz-m.z,d=Math.hypot(dx,dz);
            if(d<=1e-9) continue;
            double step=Math.min(speed,d);
            m.vx=dx/d*step;m.vz=dz/d*step;m.x+=m.vx;m.z+=m.vz;
        }
    }

    private void tickLaunched(GameState state,MonsterState m){
        m.x+=m.vx;m.y+=m.vy;m.z+=m.vz;
        m.vy-=GRAVITY;m.vy*=AIR_DRAG;m.vx*=AIR_DRAG;m.vz*=AIR_DRAG;
        if(m.y<=0.0){
            m.y=0.0;m.vy=0.0;
            if(state.tick-m.launchedAtTick>=10){m.removed=true;m.launchedUntilTick=state.tick;}
        } else if(state.tick-m.launchedAtTick>=30){m.removed=true;m.launchedUntilTick=state.tick;}
    }

    private Cell nearestCell(double x,double z){
        int row=(int)Math.floor(x),column=(int)Math.floor(z);
        if(row<0||column<0||row>=MazeModel.SIZE||column>=MazeModel.SIZE)return null;
        return maze.isTraversable(row,column)?new Cell(row,column):null;
    }
    private double centerX(int row){return row+CELL_CENTER_OFFSET;}
    private double centerZ(int column){return column+CELL_CENTER_OFFSET;}
    public boolean atWaypoint(MonsterState m,double targetX,double targetZ){
        return Math.hypot(m.x-targetX,m.z-targetZ)<WAYPOINT_TOLERANCE;
    }
    public MonsterSimulator fork(long seed){return new MonsterSimulator(maze,seed,speed);}
    public double speed(){return speed;}
    public long seed(){return seed;}
}
