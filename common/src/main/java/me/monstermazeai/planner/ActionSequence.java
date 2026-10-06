package me.monstermazeai.planner;

import me.monstermazeai.player.Action;

public record ActionSequence(Action[] actions) {
    public int length(){return actions.length;}
}