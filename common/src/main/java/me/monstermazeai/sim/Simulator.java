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

    public Simulator(PhysicsModel physics, MonsterSimulator monsters, CollisionModel collision) {
        this(physics, monsters, collision, new AbilityModel());
    }

    public Simulator(PhysicsModel physics, MonsterSimulator monsters,
                     CollisionModel collision, AbilityModel abilities) {
        this.physics=physics;
        this.monsters=monsters;
        this.collision=collision;
        this.abilities=abilities;
        this.progression=new GameProgressionModel(abilities);
    }

    public void tick(GameState state, Action action) {
        if(!state.alive) return;

        // Ability inputs happen before movement, matching a player's action for this tick.
        if (action.useAbility()) {
            abilities.activate(state);
        }

        physics.tick(state.player, action);
        monsters.tick(state);

        for(MonsterState monster: state.monsters) {
            collision.tryMonsterHit(state, monster, abilities);
        }

        progression.tick(state);

        // Monster Maze checks the Jumper charge once per server tick while airborne.
        if (state.player.y > 0.0 && state.kit == me.monstermazeai.kit.Kit.JUMPER) {
            abilities.consumeJumperCharge(state);
        }

        state.tick++;
    }

    public GameState simulate(GameState source, Action[] actions) {
        GameState state=source.copy();
        for(Action action:actions) tick(state,action);
        return state;
    }

    /**
     * Runs an isolated future rollout. The monster RNG is forked so evaluating
     * a candidate cannot change the randomness seen by another candidate.
     */
    public GameState forecast(GameState source, Action repeatedAction, int horizon, long seed) {
        GameState state = source.copy();
        Simulator predictor = new Simulator(
                physics,
                monsters.fork(seed),
                collision,
                abilities);
        for (int i = 0; i < horizon && state.alive; i++) {
            predictor.tick(state, repeatedAction);
        }
        return state;
    }
}
