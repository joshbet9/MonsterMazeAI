package me.monstermazeai.sim;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.physics.PhysicsModel;

public final class Simulator {
    private final PhysicsModel physics;
    private final MonsterSimulator monsters;
    private final CollisionModel collision;
    private final AbilityModel abilities;
    private final GameProgressionModel progression;
    private final long monsterSeed;

    public Simulator(PhysicsModel physics, MonsterSimulator monsters, CollisionModel collision) {
        this(physics, monsters, collision, new AbilityModel());
    }

    public Simulator(PhysicsModel physics, MonsterSimulator monsters, CollisionModel collision, AbilityModel abilities) {
        this.physics=physics; this.monsters=monsters; this.collision=collision; this.abilities=abilities;
        this.progression=new GameProgressionModel(abilities); this.monsterSeed=monsters.seed();
    }

    public void tick(GameState state, Action action) {
        if(!state.alive) return;
        if(action.useAbility()) abilities.activate(state);
        physics.tick(state.player, action);
        monsters.tick(state);
        for(MonsterState monster: state.monsters) collision.tryMonsterHit(state, monster, abilities);
        progression.tick(state);
        if(state.player.y>0.0 && state.kit==me.monstermazeai.kit.Kit.JUMPER) abilities.consumeJumperCharge(state);
        state.tick++;
    }

    /** Simulate a branch with a fresh deterministic monster RNG stream. */
    public GameState simulate(GameState source, Action[] actions) {
        GameState state=source.copy();
        Simulator branch=new Simulator(physics,monsters.fork(branchSeed(source.tick)),collision,abilities);
        for(Action action:actions) branch.tick(state,action);
        return state;
    }

    /** Run an isolated future with the supplied independent monster seed. */
    public GameState forecast(GameState source, Action[] actions, long seed) {
        GameState state=source.copy();
        Simulator predictor=new Simulator(physics,monsters.fork(seed),collision,abilities);
        for(Action action:actions){ if(!state.alive) break; predictor.tick(state,action); }
        return state;
    }

    public GameState forecast(GameState source, Action repeatedAction, int horizon, long seed) {
        Action[] actions=new Action[horizon];
        java.util.Arrays.fill(actions,repeatedAction);
        return forecast(source,actions,seed);
    }

    public long monsterSeed(){ return monsterSeed; }

    private long branchSeed(long tick){
        long z=monsterSeed ^ (tick+0x9E3779B97F4A7C15L);
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;
        z=(z^(z>>>27))*0x94D049BB133111EBL;
        return z^(z>>>31);
    }
}
