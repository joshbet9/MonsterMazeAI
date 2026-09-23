package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

import java.util.*;

public final class BeamSearchPlanner {
    public record Plan(ActionSequence sequence, GameState resultingState, Score score) {}

    private final Simulator simulator;
    private final Heuristic heuristic;
    private final int horizon;
    private final int beamWidth;

    public BeamSearchPlanner(Simulator simulator, Heuristic heuristic, int horizon, int beamWidth) {
        if (horizon < 1 || beamWidth < 1) throw new IllegalArgumentException();
        this.simulator=simulator;
        this.heuristic=heuristic;
        this.horizon=horizon;
        this.beamWidth=beamWidth;
    }

    public Plan plan(GameState source, double targetX, double targetZ, boolean allowJump) {
        List<Node> beam = List.of(new Node(source.copy(), new ArrayList<>()));
        Score best = heuristic.evaluate(source,targetX,targetZ);
        Node bestNode = beam.get(0);

        for (int depth=0; depth<horizon; depth++) {
            ArrayList<Node> candidates = new ArrayList<>(beam.size()*9);
            for (Node node:beam) {
                for (Action action:ActionSpace.actions(node.state, allowJump)) {
                    GameState next=simulator.simulate(node.state,new Action[]{action});
                    ArrayList<Action> seq=new ArrayList<>(node.actions);
                    seq.add(action);
                    Score score=heuristic.evaluate(next,targetX,targetZ);
                    candidates.add(new Node(next,seq,score));
                    if(score.compareTo(best)<0){best=score;bestNode=candidates.get(candidates.size()-1);}
                }
            }
            candidates.sort(Comparator.comparing(n->n.score));
            if(candidates.size()>beamWidth) candidates.subList(beamWidth,candidates.size()).clear();
            beam=candidates;
        }
        return new Plan(new ActionSequence(bestNode.actions.toArray(Action[]::new)),bestNode.state,best);
    }

    private record Node(GameState state,List<Action> actions,Score score) {
        Node(GameState state,List<Action> actions){this(state,actions,null);}
    }
}
