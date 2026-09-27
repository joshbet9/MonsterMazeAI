package me.monstermazeai.collision;

import me.monstermazeai.game.GameState;
import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.monster.MonsterState;

import java.util.Collections;
import me.monstermazeai.physics.MonsterMazeBumpModel;

/** Compatibility facade; all collision semantics live in MonsterMazeBumpModel. */
public final class CollisionModel {
    public void tryMonsterHit(GameState state, MonsterState monster) {
        MonsterMazeBumpModel.apply(state.player, Collections.singletonList(monster), state.tick);
    }

    public void tryMonsterHit(GameState state, MonsterState monster, AbilityModel abilities) {
        MonsterMazeBumpModel.apply(state.player, Collections.singletonList(monster), state.tick);
    }
}
