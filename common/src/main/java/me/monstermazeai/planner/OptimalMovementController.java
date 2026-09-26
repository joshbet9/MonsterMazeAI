package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

public final class OptimalMovementController {
    private static final double EDGE_MARGIN=0.30D;
    private static final float MAX_YAW_DELTA=30.0F;
    private static final double CORNER_LOOKAHEAD=0.45D;
    private final Simulator simulator;
    private final MonsterAwareRoutePlanner routePlanner=new MonsterAwareRoutePlanner();
    private final double tolerance;
    private String detail="UNSET";

    public OptimalMovementController(Simulator simulator,double tolerance){
        if(simulator==null||tolerance<=0.0)throw new IllegalArgumentException();
        this.simulator=simulator;this.tolerance=tolerance;
    }
    public Action nextAction(GameState state,Cell goal,boolean jump){
        if(state==null||state.maze==null||goal==null){detail="INVALID_INPUT";return Action.IDLE;}
        int sr=(int)Math.floor(state.player.x),sc=(int)Math.floor(state.player.z);
        if(!safeCell(state,sr,sc)||!safeCell(state,goal.row(),goal.column())){
            detail="UNSAFE_START_OR_GOAL";return Action.IDLE;
        }
        PlayerRoute route=routePlanner.route(state,new Cell(sr,sc),goal);
        if(route.reached(state.player.x,state.player.z,tolerance)){detail="ROUTE_REACHED";return Action.IDLE;}
        double[] t=target(state,route);
        Action a=choose(state,route,t[0],t[1],jump);
        detail="ROUTE size="+route.size()+" target="+t[0]+","+t[1]+" action="+describe(a);
        return a;
    }
    public String lastDecisionDetail(){return detail;}

    private double[] target(GameState state,PlayerRoute route){
        int index=0;
        while(index<route.size()-1&&Math.hypot(state.player.x-route.targetX(index),state.player.z-route.targetZ(index))<=tolerance)index++;
        int furthest=index;
        for(int i=index+1;i<route.size();i++){
            if(!visibleSafeSegment(state,state.player.x,state.player.z,route.targetX(i),route.targetZ(i)))break;
            furthest=i;
        }
        double tx=route.targetX(furthest),tz=route.targetZ(furthest);
        if(furthest<route.size()-1){
            double nx=route.targetX(furthest+1),nz=route.targetZ(furthest+1),len=Math.hypot(nx-tx,nz-tz);
            if(len>1e-9){double look=Math.min(CORNER_LOOKAHEAD,len*0.35D);tx+=(nx-tx)*look/len;tz+=(nz-tz)*look/len;}
        }
        return new double[]{tx,tz};
    }

    private Action choose(GameState state,PlayerRoute route,double tx,double tz,boolean jump){
        double dx=tx-state.player.x,dz=tz-state.player.z;
        float error=wrap((float)Math.toDegrees(Math.atan2(-dx,dz))-state.player.yaw);
        float yaw=clamp(error,-MAX_YAW_DELTA,MAX_YAW_DELTA);
        Action[] candidates={
            new Action(1,0,jump,true,yaw,false),
            new Action(1,error>15?-1:error<-15?1:0,jump,true,yaw,false),
            new Action(0,0,jump,true,yaw,false)
        };
        Action best=Action.IDLE;double bestScore=Double.POSITIVE_INFINITY;
        for(Action c:candidates){
            GameState next=simulator.forecast(state,c,1,simulator.monsterSeed()^state.tick);
            if(!safePosition(next.player.x,next.player.z,state))continue;
            double score=Math.hypot(next.player.x-tx,next.player.z-tz)+2.0*distanceToRoute(next.player.x,next.player.z,route);
            if(score<bestScore){bestScore=score;best=c;}
        }
        return best==Action.IDLE?new Action(0,0,jump,true,yaw,false):best;
    }

    private double distanceToRoute(double x,double z,PlayerRoute route){
        double best=Double.POSITIVE_INFINITY;
        for(int i=0;i<route.size();i++)best=Math.min(best,Math.hypot(x-route.targetX(i),z-route.targetZ(i)));
        return best;
    }
    private boolean visibleSafeSegment(GameState state,double x0,double z0,double x1,double z1){
        int n=Math.max(2,(int)Math.ceil(Math.hypot(x1-x0,z1-z0)/0.08D));
        for(int i=0;i<=n;i++){double t=i/(double)n;if(!safePosition(x0+(x1-x0)*t,z0+(z1-z0)*t,state))return false;}
        return true;
    }
    private boolean safePosition(double x,double z,GameState state){
        int r=(int)Math.floor(x),c=(int)Math.floor(z);
        if(!safeCell(state,r,c))return false;
        return x>=r+EDGE_MARGIN&&x<=r+1.0-EDGE_MARGIN&&z>=c+EDGE_MARGIN&&z<=c+1.0-EDGE_MARGIN;
    }
    private boolean safeCell(GameState s,int r,int c){return s.maze.isPhysicalFloor(r,c);}
    private static float clamp(float v,float lo,float hi){return Math.max(lo,Math.min(hi,v));}
    private static float wrap(float v){while(v>=180)v-=360;while(v<-180)v+=360;return v;}
    private static String describe(Action a){return "f="+a.forward()+",s="+a.strafe()+",jump="+a.jump()+",sprint="+a.sprint()+",yawDelta="+a.yawDelta();}
}
