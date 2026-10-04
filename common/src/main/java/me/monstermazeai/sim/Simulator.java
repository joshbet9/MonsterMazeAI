package me.monstermazeai.sim;

import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.collision.CollisionModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.GameProgressionModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.player.Action;
import me.monstermazeai.physics.PhysicsModel;
import me.monstermazeai.physics.LegacyMovementModel;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.physics.MonsterMazeBumpModel;

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

    /**
     * Start a closed-loop source-style simulation. Ability state and phase
     * progression are initialized together so the caller cannot accidentally
     * run a live game with a zero/uninitialized phase timer.
     */
    public void initialise(GameState state) {
        if (state == null) throw new IllegalArgumentException("state");
        abilities.initialiseForMode(state);
        progression.initialise(state);
        progression.syncPadSurfaces(state);
    }

    public void tick(GameState state, Action action) {
        if(!state.alive) return;
        if(action.useAbility()) abilities.activate(state);
        int jumpAmplifier = state.kit == me.monstermazeai.kit.Kit.JUMPER && state.ability.charges > 0 ? 0 : -10;
        if (physics instanceof LegacyMovementModel legacy) {
            legacy.tick(state.player, action, state.maze, jumpAmplifier);
        } else if (physics instanceof LegacyMazePhysics legacyMaze) {
            legacyMaze.tick(state.player, action, state.maze, jumpAmplifier);
        } else {
            physics.tick(state.player, action);
        }
        monsters.tick(state);
        // Use the authoritative source bump model directly in the closed-loop simulator.
        MonsterMazeBumpModel.apply(state);
        progression.tick(state);
        progression.syncPadSurfaces(state);
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

    /**
     * Counterfactual rollout helper. Returns the simulated state after each
     * tick and stops at the first stage transition so external responsibilities
     * such as next-pad selection and monster spawning are never silently omitted
     * from the learned target.
     */
    public java.util.List<GameState> forecastSnapshotsUntilStageChange(
            GameState source, Action[] actions, long seed) {
        GameState state=source.copy();
        Simulator predictor=new Simulator(physics,monsters.fork(seed),collision,abilities);
        java.util.ArrayList<GameState> snapshots=new java.util.ArrayList<>(actions.length);
        int startStage=state.stage;
        for(Action action:actions){
            if(!state.alive || state.stage!=startStage) break;
            predictor.tick(state,action);
            snapshots.add(state.copy());
            if(state.stage!=startStage) break;
        }
        return java.util.List.copyOf(snapshots);
    }

    public GameState forecastUntilStageChange(GameState source, Action[] actions, long seed) {
        java.util.List<GameState> snapshots =
                forecastSnapshotsUntilStageChange(source, actions, seed);
        return snapshots.isEmpty() ? source.copy() : snapshots.get(snapshots.size()-1).copy();
    }

    /**
     * Counterfactual one-step intervention followed by source-faithful baseline
     * continuation. The continuation is supplied by the caller so the simulator
     * remains independent of planner classes.
     */
    public java.util.List<GameState> forecastCounterfactual(
            GameState source,
            Action firstAction,
            int horizon,
            long seed,
            java.util.function.Function<GameState, Action> continuation) {
        if (source == null || firstAction == null) {
            throw new IllegalArgumentException("source and firstAction are required");
        }
        if (horizon < 1) throw new IllegalArgumentException("horizon");
        if (continuation == null) throw new IllegalArgumentException("continuation");

        GameState state = source.copy();
        Simulator predictor = new Simulator(
                physics, monsters.fork(seed), collision, abilities);
        int startStage = state.stage;
        java.util.ArrayList<GameState> snapshots =
                new java.util.ArrayList<>(horizon);

        predictor.tick(state, firstAction);
        snapshots.add(state.copy());

        if (needsExternalNextPad(state)) {
            return java.util.List.copyOf(snapshots);
        }

        for (int i = 1; i < horizon && state.alive && state.stage == startStage; i++) {
            Action next = continuation.apply(state);
            if (next == null) next = Action.IDLE;
            predictor.tick(state, next);
            snapshots.add(state.copy());
            if (needsExternalNextPad(state)) break;
        }
        return java.util.List.copyOf(snapshots);
    }

    private static boolean needsExternalNextPad(GameState state) {
        return state.previewPadRequested && state.previewPadRow < 0;
    }

    /**
     * Returns every observed future state after each simulated tick.
     * This is used by the planner's short-horizon monster predictor so risk is
     * based on the same monster movement implementation as the simulator.
     */
    public java.util.List<GameState> forecastSnapshots(GameState source, Action[] actions, long seed) {
        GameState state=source.copy();
        Simulator predictor=new Simulator(physics,monsters.fork(seed),collision,abilities);
        java.util.ArrayList<GameState> snapshots=new java.util.ArrayList<>(actions.length);
        for(Action action:actions){
            if(!state.alive) break;
            predictor.tick(state,action);
            snapshots.add(state.copy());
        }
        return java.util.List.copyOf(snapshots);
    }

    public long monsterSeed(){ return monsterSeed; }

    private long branchSeed(long tick){
        long z=monsterSeed ^ (tick+0x9E3779B97F4A7C15L);
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;
        z=(z^(z>>>27))*0x94D049BB133111EBL;
        return z^(z>>>31);
    }
}
