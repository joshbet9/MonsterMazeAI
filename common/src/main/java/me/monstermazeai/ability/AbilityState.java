package me.monstermazeai.ability;

public final class AbilityState {
    public int charges;
    public long cooldownUntilTick;
    public long activeUntilTick;
    public int activations;

    public AbilityState copy() {
        AbilityState a = new AbilityState();
        a.charges = charges;
        a.cooldownUntilTick = cooldownUntilTick;
        a.activeUntilTick = activeUntilTick;
        a.activations = activations;
        return a;
    }
}
