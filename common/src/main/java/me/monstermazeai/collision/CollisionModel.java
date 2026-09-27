package me.monstermazeai.collision;

import me.monstermazeai.game.GameState;
import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.monster.MonsterState;

import me.monstermazeai.physics.MonsterMazeBumpModel;

/** Compatibility facade; all collision semantics live in MonsterMazeBumpModel. */
public final class CollisionModel {
    public void tryMonsterHit(GameState state, MonsterState monster) {
        boolean alreadyPresent = state.monsters.contains(monster);
        if (!alreadyPresent) state.monsters.add(monster);
        try {
            MonsterMazeBumpModel.apply(state);
        } finally {
            if (!alreadyPresent) state.monsters.remove(monster);
        }
    }

    public void tryMonsterHit(GameState state, MonsterState monster, AbilityModel abilities) {
        tryMonsterHit(state, monster);
    }
}
