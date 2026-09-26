package me.monstermazeai.maze;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;
import java.util.*;

public final class MonsterAwareRoutePlanner {
    private static final double DANGER_RADIUS=3.0;
    private static final double RISK_WEIGHT=7.0;

    public PlayerRoute route(GameState state,Cell start,Cell goal){
        if(state==null||state.maze==null||start==null||goal==null)throw new IllegalArgumentException();
        if(start.equals(goal))return new PlayerRoute(List.of(start));

        Map<Cell,Double> bestScore=new HashMap<>();
        Map<Cell,Cell> previous=new HashMap<>();
        PriorityQueue<Node> q=new PriorityQueue<>(Comparator.comparingDouble((Node n)->n.score)
                .thenComparingInt(n->n.cell.row()).thenComparingInt(n->n.cell.column()));
        bestScore.put(start,0.0);
        q.add(new Node(start,0.0,0.0));

        while(!q.isEmpty()){
            Node cur=q.poll();
            if(cur.score>bestScore.getOrDefault(cur.cell,Double.POSITIVE_INFINITY)+1e-9)continue;
            if(cur.cell.equals(goal))return reconstruct(previous,start,goal);

            for(Cell next:state.maze.physicalCardinalNeighbours(cur.cell)){
                double arrival=cur.time+1.0;
                double score=cur.score+1.0+riskCost(state,next,arrival);
                Double old=bestScore.get(next);
                if(old==null||score<old-1e-9){
                    bestScore.put(next,score);
                    previous.put(next,cur.cell);
                    q.add(new Node(next,score,arrival));
                }
            }
        }
        List<Cell> fallback=new PlayerPathfinder().shortestPath(state.maze,start,goal);
        if(fallback.isEmpty())throw new IllegalArgumentException("No player route exists");
        return new PlayerRoute(fallback);
    }

    private double riskCost(GameState state,Cell cell,double arrival){
        double x=cell.row()+0.5,z=cell.column()+0.5;
        double risk=0.0;
        for(MonsterState m:state.monsters){
            if(m.removed||m.launched(state.tick)||m.frozen(state.tick))continue;
            double t=Math.min(20.0,Math.max(0.0,arrival));
            double rx=x-m.x,rz=z-m.z;
            double d=Math.hypot(rx-m.vx*t,rz-m.vz*t);
            if(d>=DANGER_RADIUS)continue;
            double proximity=(DANGER_RADIUS-d)/DANGER_RADIUS;
            double speed=Math.hypot(m.vx,m.vz);
            double closing=speed>1e-9
                    ? (m.vx*rx+m.vz*rz)/speed/Math.max(1e-9,Math.hypot(rx,rz))
                    : 0.0;
            double predictive=Math.min(1.0,Math.max(0.0,proximity+0.5*closing));
            risk+=RISK_WEIGHT*predictive*predictive;
        }
        return risk;
    }

    private PlayerRoute reconstruct(Map<Cell,Cell> previous,Cell start,Cell goal){
        ArrayList<Cell> path=new ArrayList<>();
        Cell cur=goal;
        while(cur!=null){
            path.add(cur);
            if(cur.equals(start))break;
            cur=previous.get(cur);
        }
        if(!path.get(path.size()-1).equals(start))throw new IllegalArgumentException("No player route exists");
        Collections.reverse(path);
        return new PlayerRoute(path);
    }

    private record Node(Cell cell,double score,double time){}
}
