package me.monstermazeai.planner;

import me.monstermazeai.game.GameState;

public final class Heuristic {
    public Score evaluate(GameState s, double targetX, double targetZ) {
        double dx=s.player.x-targetX, dz=s.player.z-targetZ;
        double distance=Math.hypot(dx,dz);
        double exposure=0;
        for(var m:s.monsters) {
            double mdx=s.player.x-m.x, mdz=s.player.z-m.z;
            exposure += 1.0/(1.0+Math.hypot(mdx,mdz));
        }
        double value=distance + exposure*3.0 + (s.alive?0:100000);
        return new Score(value,s.alive,s.player.health,distance,exposure);
    }
}