package me.monstermazeai.player;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlayerBehaviorTrackerTest {
    private static final class S implements PlayerBehaviorTracker.GameStateLike {
        final long tick; final PlayerState player;
        S(long tick,double x,double z,double damage) {
            this.tick=tick; player=new PlayerState(); player.x=x; player.z=z; player.damageTaken=damage;
        }
        public long tick(){return tick;} public PlayerState player(){return player;}
    }

    @Test void measuresMovementStyle() {
        PlayerBehaviorTracker t=new PlayerBehaviorTracker();
        t.observe(new S(0,0,0,0),new Action(1,0,false,true,2,false));
        t.observe(new S(20,4,0,1),new Action(0.5,0.5,true,false,4,true));
        PlayerBehaviorProfile p=t.profile();
        assertEquals(1, p.samples);
        assertEquals(0.2, p.averageHorizontalSpeed, 1e-9);
        assertEquals(0.0, p.sprintRatio, 1e-9);
        assertEquals(1.0, p.jumpRatio, 1e-9);
        assertEquals(1.0, p.strafeRatio, 1e-9);
        assertEquals(4.0, p.averageTurnPerTick, 1e-9);
        assertEquals(1.0, p.damagePerSecond, 1e-9);
        assertEquals(1.0, p.abilityUseRatePerSecond, 1e-9);
    }

    @Test void ignoresStaleTicksAndSupportsReset() {
        PlayerBehaviorTracker t=new PlayerBehaviorTracker();
        t.observe(new S(1,0,0,0),Action.IDLE);
        t.observe(new S(2,1,0,0),Action.IDLE);
        t.observe(new S(2,99,0,0),Action.forward(false));
        assertEquals(1,t.profile().samples);
        t.reset();
        assertEquals(0,t.profile().samples);
    }

    @Test void profileIsSafeBeforeAnyObservation() {
        PlayerBehaviorProfile p=new PlayerBehaviorTracker().profile();
        assertEquals(0,p.samples);
        assertEquals(0,p.averageHorizontalSpeed,0);
    }
}
