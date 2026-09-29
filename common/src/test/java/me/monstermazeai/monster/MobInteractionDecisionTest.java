package me.monstermazeai.monster;

import me.monstermazeai.game.GameState;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class MobInteractionDecisionTest {
    @Test
    void choosesMonsterWhenDeadlineMakesOrdinaryTravelImpossible() {
        GameState s = new GameState();
        s.activePadRow = 20;
        s.activePadColumn = 10;
        s.phaseTicksRemaining = 20;
        s.player.x = 10.0;
        s.player.y = 0.0;
        s.player.z = 10.0;
        s.player.health = 20.0;

        MonsterState monster = new MonsterState(7, 9.0, 0.0, 10.0);
        s.monsters.add(monster);

        assertSame(monster, MobInteractionDecision.chooseIntentionalBump(s));
    }

    @Test
    void refusesIntentionalBumpAtTwoHearts() {
        GameState s = new GameState();
        s.activePadRow = 20;
        s.activePadColumn = 10;
        s.phaseTicksRemaining = 1;
        s.player.x = 10.0;
        s.player.y = 0.0;
        s.player.z = 10.0;
        s.player.health = 4.0;
        s.monsters.add(new MonsterState(7, 9.0, 0.0, 10.0));

        assertNull(MobInteractionDecision.chooseIntentionalBump(s));
    }

    @Test
    void refusesMonsterOnWrongSideBecauseBumpWouldMoveAwayFromPad() {
        GameState s = new GameState();
        s.activePadRow = 20;
        s.activePadColumn = 10;
        s.phaseTicksRemaining = 1;
        s.player.x = 10.0;
        s.player.y = 0.0;
        s.player.z = 10.0;
        s.player.health = 20.0;
        s.monsters.add(new MonsterState(11, 11.0, 0.0, 10.0));

        assertNull(MobInteractionDecision.chooseIntentionalBump(s));
    }

    @Test
    void doesNotSpendHealthWhenOrdinaryTravelStillFitsDeadline() {
        GameState s = new GameState();
        s.activePadRow = 20;
        s.activePadColumn = 10;
        s.phaseTicksRemaining = 100;
        s.player.x = 10.0;
        s.player.y = 0.0;
        s.player.z = 10.0;
        s.player.health = 20.0;
        s.monsters.add(new MonsterState(11, 9.0, 0.0, 10.0));

        assertNull(MobInteractionDecision.chooseIntentionalBump(s));
    }
}
