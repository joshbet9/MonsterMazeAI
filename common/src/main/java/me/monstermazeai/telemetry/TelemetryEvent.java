package me.monstermazeai.telemetry;

import me.monstermazeai.adapter.LegacyWorldObservation;
import me.monstermazeai.adapter.LegacyAction;
import me.monstermazeai.game.GameState;

/** Immutable one-observation decision record suitable for JSONL telemetry. */
public final class TelemetryEvent {
    public final long tick;
    public final long latencyNanos;
    public final boolean inMaze;
    public final int stage;
    public final int padRow;
    public final int padColumn;
    public final double playerX;
    public final double playerZ;
    public final String kit;
    public final LegacyAction action;
    public final String abilityReason;

    public TelemetryEvent(long tick, long latencyNanos, GameState state,
                          LegacyAction action, String abilityReason) {
        this.tick=tick; this.latencyNanos=latencyNanos;
        this.inMaze=state.inMonsterMaze; this.stage=state.stage;
        this.padRow=state.activePadRow; this.padColumn=state.activePadColumn;
        this.playerX=state.player.x; this.playerZ=state.player.z;
        this.kit=state.kit == null ? "" : state.kit.name();
        this.action=action == null ? LegacyAction.IDLE : action;
        this.abilityReason=abilityReason == null ? "" : abilityReason;
    }

    public String toJson() {
        return "{\"tick\":"+tick+",\"latencyNanos\":"+latencyNanos
                +",\"inMaze\":"+inMaze+",\"stage\":"+stage
                +",\"padRow\":"+padRow+",\"padColumn\":"+padColumn
                +",\"playerX\":"+playerX+",\"playerZ\":"+playerZ
                +",\"kit\":\""+escape(kit)+"\",\"forward\":"+action.forward
                +",\"strafe\":"+action.strafe+",\"jump\":"+action.jump
                +",\"sprint\":"+action.sprint+",\"yawDelta\":"+action.yawDelta
                +",\"useAbility\":"+action.useAbility+",\"abilityReason\":\""
                +escape(abilityReason)+"\"}";
    }

    private static String escape(String s) {
        return s.replace("\\","\\\\").replace("\"","\\\"")
                .replace("\n","\\n").replace("\r","\\r");
    }
}
