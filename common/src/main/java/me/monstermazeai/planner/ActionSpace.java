package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;
import java.util.ArrayList;
import java.util.List;

public final class ActionSpace {
    private ActionSpace(){}

    public static List<Action> actions(GameState game,boolean allowJump){
        return actions(game,Double.NaN,Double.NaN,allowJump);
    }

    /** Adds target-directed controls so the search can make an immediate,
     * physically legal camera turn instead of needing a scripted 90-degree turn. */
    public static List<Action> actions(GameState game,double targetX,double targetZ,boolean allowJump){
        ArrayList<Action> out=new ArrayList<>(movementActions(allowJump));
        if(!Double.isNaN(targetX)&&!Double.isNaN(targetZ)){
            double dx=targetX-game.player.x,dz=targetZ-game.player.z;
            if(Math.hypot(dx,dz)>1e-6){
                float desired=(float)Math.toDegrees(Math.atan2(-dx,dz));
                float delta=normalise(desired-game.player.yaw);
                add(out,new Action(1,0,false,true,delta,false));
                if(allowJump) add(out,new Action(1,0,true,true,delta,false));
            }
        }
        if(canUseAbility(game)){
            ArrayList<Action> withAbility=new ArrayList<>(out.size());
            for(Action a:out) withAbility.add(new Action(a.forward(),a.strafe(),a.jump(),a.sprint(),a.yawDelta(),true));
            out.addAll(withAbility);
        }
        return out;
    }

    private static float normalise(float angle){
        while(angle>=180) angle-=360;
        while(angle< -180) angle+=360;
        return angle;
    }

    private static boolean canUseAbility(GameState game){
        return switch(game.kit){
            case JUMPER->false;
            case SLOWBALLER->game.mode!=me.monstermazeai.game.Mode.ORIGINAL && game.tick>=game.ability.cooldownUntilTick;
            case BODY_BUILDER->game.mode!=me.monstermazeai.game.Mode.ORIGINAL && game.ability.activations>0 && game.ability.activeUntilTick<=game.tick;
            case REPULSOR->game.ability.charges>0;
            case MAVERICK->false;
        };
    }

    public static List<Action> movementActions(boolean allowJump){return movementActions(allowJump,true);}
    public static List<Action> movementActions(boolean allowJump,boolean includeIdle){
        ArrayList<Action> out=new ArrayList<>();
        add(out,new Action(1,0,false,true,0,false));
        add(out,new Action(0,1,false,true,0,false));
        add(out,new Action(0,-1,false,true,0,false));
        add(out,new Action(-1,0,false,true,0,false));
        add(out,new Action(1,0,false,true,15,false));
        add(out,new Action(1,0,false,true,-15,false));
        if(allowJump){
            add(out,new Action(1,0,true,true,0,false));
            add(out,new Action(0,1,true,true,0,false));
            add(out,new Action(0,-1,true,true,0,false));
            add(out,new Action(-1,0,true,true,0,false));
            add(out,new Action(1,0,true,true,15,false));
            add(out,new Action(1,0,true,true,-15,false));
        }
        if(includeIdle)add(out,Action.IDLE);
        return out;
    }

    private static void add(List<Action> out,Action action){if(!out.contains(action))out.add(action);}
}
