package me.monstermazeai.planner;
import me.monstermazeai.ability.AbilityDecision;
import me.monstermazeai.ability.AbilityUseGate;
import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;

public final class RobustLiveController {
    private final LiveObjectiveController objective;
    private final AbilityUseGate abilityGate=new AbilityUseGate();
    private long lastTick=Long.MIN_VALUE;
    private String lastDecisionDetail="UNSET";

    public RobustLiveController(LiveObjectiveController objective){
        if(objective==null)throw new IllegalArgumentException("objective");
        this.objective=objective;
    }
    public Action nextAction(GameState state,boolean allowJump){
        if(!validLiveState(state)){lastDecisionDetail="INVALID_LIVE_STATE";reset();return Action.IDLE;}
        if(state.tick<=lastTick){lastDecisionDetail="STALE_TICK stateTick="+state.tick+" lastTick="+lastTick;return Action.IDLE;}
        lastTick=state.tick;
        Action action=objective.nextAction(state,allowJump);
        if(action==Action.IDLE){
            lastDecisionDetail="OBJECTIVE_IDLE reason="+objective.lastDecisionReason()+" detail="+objective.lastDecisionDetail();
            return Action.IDLE;
        }
        if(AbilityDecision.shouldUse(state)&&abilityGate.allow(state)){
            abilityGate.record(state);
            Action ability=new Action(action.forward(),action.strafe(),action.jump(),action.sprint(),action.yawDelta(),true);
            lastDecisionDetail="ABILITY_OPTIMIZED detail="+objective.lastDecisionDetail()+" output="+describe(ability);
            return ability;
        }
        lastDecisionDetail="PASS detail="+objective.lastDecisionDetail()+" output="+describe(action);
        return action;
    }
    public String lastDecisionDetail(){return lastDecisionDetail;}
    public void reset(){lastTick=Long.MIN_VALUE;lastDecisionDetail="RESET";}
    private static boolean validLiveState(GameState s){
        return s!=null&&s.inMonsterMaze&&s.alive&&!s.completed&&s.maze!=null
                &&s.activePadRow>=0&&s.activePadColumn>=0;
    }
    private static String describe(Action a){
        return "f="+a.forward()+",s="+a.strafe()+",jump="+a.jump()+",sprint="+a.sprint()
                +",yawDelta="+a.yawDelta()+",ability="+a.useAbility();
    }
}
