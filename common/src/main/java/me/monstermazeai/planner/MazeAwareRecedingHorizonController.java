package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.maze.MonsterAwareRoutePlanner;
import me.monstermazeai.maze.PlayerRoute;
import me.monstermazeai.player.Action;

public final class MazeAwareRecedingHorizonController {
    private final OptimalMovementController movement;
    private final double waypointTolerance;
    private final MonsterAwareRoutePlanner routePlanner=new MonsterAwareRoutePlanner();
    private String lastDecisionDetail="UNSET";

    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,int executionTicks){
        this(planner,executionTicks,0.75);
    }
    public MazeAwareRecedingHorizonController(BeamSearchPlanner planner,int executionTicks,double waypointTolerance){
        if(planner==null||executionTicks<1||waypointTolerance<=0.0)throw new IllegalArgumentException();
        this.movement=new OptimalMovementController(planner.simulator(),waypointTolerance);
        this.waypointTolerance=waypointTolerance;
    }
    public String lastDecisionDetail(){return lastDecisionDetail;}

    public Action[] nextActions(GameState state,Cell goal,boolean allowJump){
        if(state==null){lastDecisionDetail="NULL_STATE";return new Action[]{Action.IDLE};}
        if(state.maze==null){lastDecisionDetail="NO_MAZE";return new Action[]{Action.IDLE};}
        int sr=(int)Math.floor(state.player.x),sc=(int)Math.floor(state.player.z);
        if(sr<0||sc<0||sr>=99||sc>=99){
            lastDecisionDetail="PLAYER_CELL_OUT_OF_BOUNDS row="+sr+" col="+sc;
            return new Action[]{Action.IDLE};
        }
        PlayerRoute route=routePlanner.route(state,new Cell(sr,sc),goal);
        if(route.reached(state.player.x,state.player.z,waypointTolerance)){
            lastDecisionDetail="ROUTE_REACHED size="+route.size()+" goal="+goal.row()+","+goal.column();
            return new Action[]{Action.IDLE};
        }
        Action action=movement.nextAction(state,goal,allowJump);
        lastDecisionDetail=movement.lastDecisionDetail()+" routeGoal="+goal.row()+","+goal.column()
                +" action="+describe(action);
        return new Action[]{action};
    }
    private static String describe(Action a){
        return "f="+a.forward()+",s="+a.strafe()+",jump="+a.jump()+",sprint="+a.sprint()
                +",yawDelta="+a.yawDelta()+",ability="+a.useAbility();
    }
}
