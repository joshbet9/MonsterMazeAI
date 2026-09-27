package me.monstermazeai.collision;

import me.monstermazeai.game.GameState;
import me.monstermazeai.monster.MonsterState;

import java.util.Collections;
import me.monstermazeai.physics.MonsterMazeBumpModel;

/** Compatibility facade; all collision semantics live in MonsterMazeBumpModel. */
public final class CollisionModel {
    public void tryMonsterHit(GameState state, MonsterState monster) {
        MonsterMazeBumpModel.apply(state, Collections.singletonList(monster), state.tick);
    }
}
