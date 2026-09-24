package me.monstermazeai.competitor;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerSpecificNpcModel;
import me.monstermazeai.runtime.AutonomousMonsterMazeAgent;
import me.monstermazeai.runtime.PlayerSpecificNpcAgent;
import me.monstermazeai.planner.BeamSearchPlanner;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.planner.LiveObjectiveController;
import me.monstermazeai.planner.RobustLiveController;
import me.monstermazeai.sim.Simulator;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.collision.CollisionModel;

import java.util.Random;

/** Stateful competitor with an independent autonomous controller and playstyle. */
public final class MonsterMazeCompetitor {
    private final CompetitorDefinition definition;
    private PlayerSpecificNpcAgent agent;
    private long mazeSignature = Long.MIN_VALUE;

    public MonsterMazeCompetitor(CompetitorDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition");
        this.definition=definition;
    }

    public CompetitorDefinition definition() { return definition; }

    public Action decide(GameState state) {
        if (state == null) {
            reset();
            return Action.IDLE;
        }
        long signature=signature(state);
        if (agent == null || signature != mazeSignature) {
            buildAgent(state);
            mazeSignature=signature;
        }
        state.kit=definition.kit;
        return agent.decide(state, definition.allowJump);
    }

    public void reset() {
        if (agent != null) agent.reset();
        agent=null;
        mazeSignature=Long.MIN_VALUE;
    }

    private void buildAgent(GameState state) {
        if (state.maze == null) {
            reset();
            return;
        }
        Simulator simulator=new Simulator(
                new LegacyMazePhysics(),
                new MonsterSimulator(state.maze, new Random(state.tick ^ 0x4D4D4159L), 0.0),
                new CollisionModel());
        RobustLiveController robust=new RobustLiveController(
                new LiveObjectiveController(
                        new MazeAwareRecedingHorizonController(
                                new BeamSearchPlanner(simulator, new me.monstermazeai.planner.Heuristic(), 12, 32), 1)));
        AutonomousMonsterMazeAgent autonomous=new AutonomousMonsterMazeAgent(robust);
        agent=new PlayerSpecificNpcAgent(autonomous,
                new PlayerSpecificNpcModel(definition.profile));
    }

    private static long signature(GameState state) {
        long h=1469598103934665603L;
        h^=state.mazePattern; h*=1099511628211L;
        h^=state.activePadRow; h*=1099511628211L;
        h^=state.activePadColumn; h*=1099511628211L;
        if (state.maze != null) {
            for (int r=0;r<99;r++) for (int c=0;c<99;c++) {
                h^=state.maze.raw(r,c); h*=1099511628211L;
            }
        }
        return h;
    }
}
