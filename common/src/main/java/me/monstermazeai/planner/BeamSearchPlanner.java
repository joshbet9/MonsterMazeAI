package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import me.monstermazeai.sim.Simulator;
import me.monstermazeai.sim.MonsterTrajectoryPredictor;
import me.monstermazeai.monster.MonsterState;
import java.util.*;

public final class BeamSearchPlanner {
    public record Plan(ActionSequence sequence, GameState resultingState, Score score,
                       boolean padReached, String decisionReason) {}

    private final Simulator simulator;
    private final Heuristic heuristic;
    private final int horizon;
    private final int beamWidth;
    private final MonsterTrajectoryPredictor monsterPredictor;

    public BeamSearchPlanner(Simulator simulator, Heuristic heuristic, int horizon, int beamWidth) {
        if(horizon<1||beamWidth<1) throw new IllegalArgumentException();
        this.simulator=simulator;this.heuristic=heuristic;this.horizon=horizon;this.beamWidth=beamWidth;
        this.monsterPredictor=new MonsterTrajectoryPredictor(simulator);
    }

    public Simulator simulator() { return simulator; }\n\n    public Plan plan(GameState source,double targetX,double targetZ,boolean allowJump) {
        List<Node> beam=List.of(new Node(source.copy(),new ArrayList<>(),heuristic.evaluate(source,targetX,targetZ)));
        Node bestNode=beam.get(0); Score best=beam.get(0).score;
        int searchHorizon=effectiveHorizon(source);

        // Emergency mode gets a deterministic physically-simulated incumbent.
        // This is not a shortcut around planning: it is a guaranteed seed
        // trajectory that gives the beam a valid deadline-feasible option when
        // the full 15-second search space is aggressively pruned.
        if (isEmergency(source)) {
            Node emergencySeed = buildEmergencySeed(source, targetX, targetZ, allowJump, searchHorizon);
            if (emergencySeed != null && emergencySeed.state.padReached) {
                return new Plan(new ActionSequence(emergencySeed.actions.toArray(Action[]::new)),
                        emergencySeed.state, emergencySeed.score, true,
                        "Emergency deadline mode: reaches the active Safe Pad with a physically simulated trajectory.");
            }
        }

        for(int depth=0;depth<searchHorizon;depth++){
            ArrayList<Node> candidates=new ArrayList<>(beam.size()*20);
            for(Node node:beam){
                for(Action action:ActionSpace.actions(node.state,targetX,targetZ,allowJump)){
                    GameState next=simulator.simulate(node.state,new Action[]{action});
                    ArrayList<Action> seq=new ArrayList<>(node.actions);seq.add(action);
                    Score score=trajectoryScore(heuristic.evaluate(next,targetX,targetZ),depth+1,next,source);
                    if (hasNearbyMonster(next, 10.0)) {
                        MonsterTrajectoryPredictor.Prediction risk =
                                monsterPredictor.predict(next, action, 6,
                                        simulator.monsterSeed() ^ next.tick);
                        score = addRiskCost(score, risk.riskCost());
                    }
                    Node candidate=new Node(next,seq,score);
                    candidates.add(candidate);
                    if(next.padReached || score.compareTo(best)<0){
                        if(next.padReached || !bestNode.state.padReached){best=score;bestNode=candidate;}
                    }
                }
            }

            candidates.sort(Comparator.comparing(n->n.score));
            List<Node> survivors=new ArrayList<>(beamWidth);
            Set<String> seen=new HashSet<>();
            for(Node candidate:candidates){
                if(!candidate.state.alive) continue;
                String key=stateKey(candidate.state);
                if(!seen.add(key)) continue;
                survivors.add(candidate);
                if(survivors.size()>=beamWidth) break;
            }
            if(survivors.isEmpty()) break;
            beam=survivors;

            for(Node candidate:beam){
                if(candidate.state.padReached){
                    bestNode=candidate;best=candidate.score;break;
                }
            }
        }

        String reason=explain(bestNode.state,source,targetX,targetZ);
        return new Plan(new ActionSequence(bestNode.actions.toArray(Action[]::new)),
                bestNode.state,best,bestNode.state.padReached,reason);
    }

    private int effectiveHorizon(GameState state){
        // A 15-second floor is a real deadline, not merely another heuristic.
        // Give emergency routing the full remaining budget when the configured
        // look-ahead is shorter, capped to avoid unbounded search.
        if(state.phaseTicksRemaining>0 && state.phaseTicksRemaining<=15*20)
            return Math.min(15*20,Math.max(horizon,state.phaseTicksRemaining));
        return horizon;
    }

    private String stateKey(GameState s){
        double cellX=Math.floor(s.player.x),cellZ=Math.floor(s.player.z);
        int yawBucket=Math.round(s.player.yaw/15.0f);
        int healthBucket=(int)Math.floor(s.player.health);
        return cellX+":"+cellZ+":"+yawBucket+":"+s.player.grounded+":"+healthBucket+
                ":"+s.ability.charges+":"+s.ability.activations+":"+s.phaseTicksRemaining;
    }

    private boolean isEmergency(GameState state) {
        return state.phaseTicksRemaining > 0 && state.phaseTicksRemaining <= 15 * 20;
    }

    private Node buildEmergencySeed(GameState source, double targetX, double targetZ,
                                    boolean allowJump, int maxTicks) {
        GameState state = source.copy();
        ArrayList<Action> actions = new ArrayList<>();

        for (int i = 0; i < maxTicks && state.alive && !state.padReached; i++) {
            double dx = targetX - state.player.x;
            double dz = targetZ - state.player.z;
            if (Double.isNaN(dx) || Double.isNaN(dz)) return null;

            float desiredYaw = (float) Math.toDegrees(Math.atan2(-dx, dz));
            float delta = desiredYaw - state.player.yaw;
            while (delta >= 180.0F) delta -= 360.0F;
            while (delta < -180.0F) delta += 360.0F;

            Action action = new Action(1, 0, false, true, delta, false);
            state = simulator.simulate(state, new Action[]{action});
            actions.add(action);
        }

        if (!state.padReached) return null;
        Score score = trajectoryScore(heuristic.evaluate(state, targetX, targetZ),
                actions.size(), state, source);
        return new Node(state, actions, score);
    }

    private String explain(GameState result,GameState source,double tx,double tz){
        if(!result.alive)return "Branch dies before reaching the active Safe Pad.";
        if(result.padReached)return "Reaches the active Safe Pad within the planned horizon.";
        double before=Math.hypot(source.player.x-tx,source.player.z-tz);
        double after=Math.hypot(result.player.x-tx,result.player.z-tz);
        if(source.phaseTicksRemaining<=15*20)
            return "Emergency deadline mode: prioritises physical pad reach before the timer expires.";
        if(after<before)return "Moves toward the active Safe Pad using the simulated trajectory.";
        return "Preserves survival while evaluating a better future trajectory.";
    }

    private Score trajectoryScore(Score h,int depth,GameState state,GameState source){
        double value=h.value()+depth*0.02D;
        double damage=Math.max(0.0, state.player.damageTaken-source.player.damageTaken);
        // Damage is a time-equivalent cost, not a hard avoidance rule. A
        // sufficiently large time saving can therefore justify taking a hit.
        value+=damage*2.5D;
        return new Score(value,h.alive(),h.health(),h.padDistance(),h.monsterExposure());
    }

    private Score addRiskCost(Score score, double riskCost) {
        return new Score(
                score.value() + riskCost * 3.0D,
                score.alive(),
                score.health(),
                score.padDistance(),
                score.monsterExposure() + riskCost);
    }

    private boolean hasNearbyMonster(GameState state, double radius) {
        double radiusSq = radius * radius;
        for (MonsterState monster : state.monsters) {
            if (monster.removed || monster.launched(state.tick)
                    || monster.frozen(state.tick)) continue;
            double dx = state.player.x - monster.x;
            double dz = state.player.z - monster.z;
            if (dx * dx + dz * dz <= radiusSq) return true;
        }
        return false;
    }

    private record Node(GameState state,List<Action> actions,Score score){}
}
