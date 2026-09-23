package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;

import java.util.ArrayList;
import java.util.List;

public final class ActionSpace {
    private ActionSpace() {}

    public static List<Action> actions(GameState game, boolean allowJump) {
        ArrayList<Action> out = new ArrayList<>(movementActions(allowJump));

        // Ability activation is deliberately an action dimension. This lets
        // the search discover emergency use rather than requiring a scripted
        // "use ability when close" rule.
        if (canUseAbility(game)) {
            ArrayList<Action> withAbility = new ArrayList<>(out.size());
            for (Action a : out) {
                withAbility.add(new Action(a.forward(), a.strafe(), a.jump(),
                        a.sprint(), a.yawDelta(), true));
            }
            out.addAll(withAbility);
        }
        return out;
    }

    private static boolean canUseAbility(GameState game) {
        return switch (game.kit) {
            case JUMPER -> false;
            case SLOWBALLER -> game.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && game.tick >= game.ability.cooldownUntilTick;
            case BODY_BUILDER -> game.mode != me.monstermazeai.game.Mode.ORIGINAL
                    && game.ability.activations > 0
                    && game.ability.activeUntilTick <= game.tick;
            case REPULSOR -> game.ability.charges > 0;
            case MAVERICK -> false;
        };
    }

    public static List<Action> movementActions(boolean allowJump) {
        return movementActions(allowJump, true);
    }

    public static List<Action> movementActions(boolean allowJump, boolean includeIdle) {
        java.util.ArrayList<Action> out = new java.util.ArrayList<>();
        // Movement is expressed relative to the player's current yaw.
        // Keep the cardinal controls, then add small deliberate heading
        // changes so the planner can corner without a scripted turn.
        add(out, new Action(1,0,false,true,0,false));
        add(out, new Action(0,1,false,true,0,false));
        add(out, new Action(0,-1,false,true,0,false));
        add(out, new Action(-1,0,false,true,0,false));
        add(out, new Action(1,0,false,true,15,false));
        add(out, new Action(1,0,false,true,-15,false));
        if (allowJump) {
            add(out, new Action(1,0,true,true,0,false));
            add(out, new Action(0,1,true,true,0,false));
            add(out, new Action(0,-1,true,true,0,false));
            add(out, new Action(-1,0,true,true,0,false));
            add(out, new Action(1,0,true,true,15,false));
            add(out, new Action(1,0,true,true,-15,false));
        }
        if (includeIdle) add(out, Action.IDLE);
        return out;
    }

    private static void add(List<Action> out, Action action) {
        if (!out.contains(action)) out.add(action);
    }
}
