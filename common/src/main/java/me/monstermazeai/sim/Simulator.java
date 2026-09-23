package me.monstermazeai.sim;

import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.physics.PhysicsModel;

public final class Simulator {
    private final PhysicsModel physics;
    private final MonsterSimulator monsters;
    private final CollisionModel collision;

    public Simulator(PhysicsModel physics, MonsterSimulator monsters, CollisionModel collision) {
        this.physics=physics; this.monsters=monsters; this.collision=collision;
    }

    public void tick(GameState state, Action action) {
        if(!state.alive) return;
        physics.tick(state.player, action);
        monsters.tick(state);
        for(MonsterState monster: state.monsters) collision.tryMonsterHit(state, monster);
        state.tick++;
        if(state.phaseTicksRemaining>0) state.phaseTicksRemaining--;
    }

    public GameState simulate(GameState source, Action[] actions) {
        GameState state=source.copy();
        for(Action action:actions) tick(state,action);
        return state;
    }
}