package me.monstermazeai.ability;
import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;

public final class AbilityUseGate {
    private long nextAllowedTick=Long.MIN_VALUE;
    private Kit lastKit;

    public boolean allow(GameState state){
        if(state==null||state.kit==null)return false;
        if(state.kit!=lastKit)return true;
        return state.tick>=nextAllowedTick;
    }
    public void record(GameState state){
        if(state==null)return;
        lastKit=state.kit;
        switch(state.kit){
            case SLOWBALLER: nextAllowedTick=state.tick+600;break;
            case BODY_BUILDER: nextAllowedTick=state.tick+200;break;
            default: nextAllowedTick=state.tick+1;
        }
    }
}
