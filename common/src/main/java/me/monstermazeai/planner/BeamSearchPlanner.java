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
        List<Node> beam = List.of(new Node(source.copy(), new ArrayList<>(), null));
        Score best = heuristic.evaluate(source,targetX,targetZ);
        Node bestNode = beam.get(0);

        for (int depth=0; depth<horizon; depth++) {
            ArrayList<Node> candidates = new ArrayList<>(beam.size()*18);

            for (Node node:beam) {
                for (Action action:ActionSpace.actions(node.state, allowJump)) {
                    GameState next=simulator.simulate(node.state,new Action[]{action});
                    ArrayList<Action> seq=new ArrayList<>(node.actions);
                    seq.add(action);

                    Score immediate = heuristic.evaluate(next,targetX,targetZ);

                    // Look beyond the single tick. A candidate can look good
                    // now while its current trajectory walks directly into a
                    // monster a few ticks later. Use independent stochastic
                    // rollouts so monster intersection choices do not leak
                    // between beam branches.
                    GameState futureA = simulator.forecast(next, action, 20,
                            rolloutSeed(next, action, 0));
                    GameState futureB = simulator.forecast(next, action, 20,
                            rolloutSeed(next, action, 1));
                    Score scoreA = heuristic.evaluate(futureA,targetX,targetZ);
                    Score scoreB = heuristic.evaluate(futureB,targetX,targetZ);
                    Score score = combinedScore(immediate, scoreA, scoreB);

                    Node candidate=new Node(next,seq,score);
                    candidates.add(candidate);

                    if (score.compareTo(best)<0) {
                        best=score;
                        bestNode=candidate;
                    }
                }
            }

            // Always retain at least one surviving candidate when available.
            candidates.sort(Comparator.comparing(n->n.score));
            List<Node> survivors = new ArrayList<>();
            for (Node candidate : candidates) {
                if (candidate.state.alive) {
                    survivors.add(candidate);
                    if (survivors.size() >= beamWidth) break;
                }
            }

            // If every candidate dies, retain the least-bad branch so the
            // planner can still recover once a different action becomes
            // available at the next replanning point.
            if (survivors.isEmpty()) {
                survivors.add(candidates.get(0));
            }
            beam=survivors;
        }

        return new Plan(new ActionSequence(bestNode.actions.toArray(Action[]::new)),bestNode.state,best);
    }

    
    private Score combinedScore(Score immediate, Score a, Score b) {
        // Keep the actual next state dominant, but penalise candidates whose
        // plausible futures become unsafe or miss the timer.
        double future = Math.min(a.value(), b.value()) * 0.25
                + Math.max(a.value(), b.value()) * 0.25;
        double value = immediate.value() * 0.50 + future;
        return new Score(value,
                immediate.alive() && (a.alive() || b.alive()),
                Math.min(a.health(), b.health()),
                immediate.padDistance(),
                Math.max(a.monsterExposure(), b.monsterExposure()));
    }

    private long rolloutSeed(GameState state, Action action, int branch) {
        long h = state.tick * 0x9E3779B97F4A7C15L;
        h ^= ((long) action.hashCode() << 32) ^ action.hashCode();
        h ^= branch * 0xBF58476D1CE4E5B9L;
        h ^= Double.doubleToLongBits(state.player.x);
        h ^= Double.doubleToLongBits(state.player.z);
        return h;
    }

    private record Node(GameState state,List<Action> actions,Score score) {}
}
