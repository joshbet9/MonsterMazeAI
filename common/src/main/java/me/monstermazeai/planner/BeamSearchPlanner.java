package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;

import java.util.*;

public final class BeamSearchPlanner {
    public record Plan(ActionSequence sequence, GameState resultingState, Score score,
                       boolean padReached, String decisionReason) {}

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
        List<Node> beam = List.of(new Node(source.copy(), new ArrayList<>(), null));
        Node bestNode = beam.get(0);
        Score best = heuristic.evaluate(source,targetX,targetZ);

        for (int depth=0; depth<horizon; depth++) {
            ArrayList<Node> candidates = new ArrayList<>(beam.size()*18);

            for (Node node:beam) {
                for (Action action:ActionSpace.actions(node.state, allowJump)) {
                    GameState next=simulator.simulate(node.state,new Action[]{action});
                    ArrayList<Action> seq=new ArrayList<>(node.actions);
                    seq.add(action);

                    Score score = trajectoryScore(
                            heuristic.evaluate(next, targetX, targetZ),
                            depth + 1,
                            next);

                    Node candidate=new Node(next,seq,score);
                    candidates.add(candidate);

                    if (next.padReached || score.compareTo(best)<0) {
                        if (next.padReached || !bestNode.state.padReached) {
                            best=score;
                            bestNode=candidate;
                        }
                    }
                }
            }

            candidates.sort(Comparator.comparing(n->n.score));
            List<Node> survivors=new ArrayList<>(beamWidth);
            for(Node candidate:candidates){
                if(candidate.state.alive){
                    survivors.add(candidate);
                    if(survivors.size()>=beamWidth) break;
                }
            }
            if(survivors.isEmpty()) survivors.add(candidates.get(0));
            beam=survivors;

            for(Node candidate:beam){
                if(candidate.state.padReached){
                    bestNode=candidate;
                    best=candidate.score;
                    break;
                }
            }
        }

        String reason = explain(bestNode.state, source, targetX, targetZ);
        return new Plan(
                new ActionSequence(bestNode.actions.toArray(Action[]::new)),
                bestNode.state, best, bestNode.state.padReached, reason);
    }

    private String explain(GameState result, GameState source, double tx, double tz) {
        if (!result.alive) return "Branch dies before reaching the active Safe Pad.";
        if (result.padReached) return "Reaches the active Safe Pad within the planned horizon.";
        double before=Math.hypot(source.player.x-tx,source.player.z-tz);
        double after=Math.hypot(result.player.x-tx,result.player.z-tz);
        if (result.phaseTicksRemaining < source.phaseTicksRemaining)
            return "Reduces time-to-pad while preserving enough timer margin.";
        if (after < before) return "Moves toward the active Safe Pad.";
        return "Preserves survival while evaluating a better future trajectory.";
    }

    private Score trajectoryScore(Score heuristicScore, int depth, GameState state) {
        // Beam depth is already the planner's exact look-ahead. A tiny
        // per-tick cost makes otherwise equivalent trajectories prefer
        // reaching the same state sooner, instead of relying on the old
        // repeated-action rollout heuristic.
        double value = heuristicScore.value() + depth * 0.02D;
        return new Score(value, heuristicScore.alive(), heuristicScore.health(),
                heuristicScore.padDistance(), heuristicScore.monsterExposure());
    }

    private record Node(GameState state,List<Action> actions,Score score){}
}
