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

/** Stateful competitor with an independent autonomous controller and playstyle. */
public final class MonsterMazeCompetitor {
    private final CompetitorDefinition definition;
    private final AutonomousMonsterMazeAgent autonomous;
    private final PlayerSpecificNpcAgent agent;

    public MonsterMazeCompetitor(CompetitorDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition");
        this.definition=definition;
        this.autonomous=new AutonomousMonsterMazeAgent(
                new RobustLiveController(new LiveObjectiveController(
                        new MazeAwareRecedingHorizonController(new BeamSearchPlanner()))));
        this.agent=new PlayerSpecificNpcAgent(autonomous,
                new PlayerSpecificNpcModel(definition.profile));
    }

    public CompetitorDefinition definition() { return definition; }

    public Action decide(GameState state) {
        if (state != null) state.kit=definition.kit;
        return agent.decide(state, definition.allowJump);
    }

    public void reset() { agent.reset(); }
}
