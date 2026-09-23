package me.monstermazeai.planner;

import me.monstermazeai.player.Action;

import java.util.List;

public final class ActionSpace {
    private ActionSpace() {}

    public static List<Action> movementActions(boolean allowJump) {
        return movementActions(allowJump, true);
    }

    public static List<Action> movementActions(boolean allowJump, boolean includeIdle) {
        java.util.ArrayList<Action> out = new java.util.ArrayList<>();
        add(out, new Action(1,0,false,true,0,false));
        add(out, new Action(0,1,false,true,0,false));
        add(out, new Action(0,-1,false,true,0,false));
        add(out, new Action(-1,0,false,true,0,false));
        if (allowJump) {
            add(out, new Action(1,0,true,true,0,false));
            add(out, new Action(0,1,true,true,0,false));
            add(out, new Action(0,-1,true,true,0,false));
            add(out, new Action(-1,0,true,true,0,false));
        }
        if (includeIdle) add(out, Action.IDLE);
        return out;
    }

    private static void add(List<Action> out, Action action) {
        if (!out.contains(action)) out.add(action);
    }
}
