package me.monstermazeai.ability;

import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.game.PadModel;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.monster.MonsterState;

public final class AbilityModel {
    private static final long JUMPER_RECHARGE_TICKS = 15; // 750 ms at 20 TPS
    private static final long CRYO_COOLDOWN_TICKS = 600; // 30 s
    private static final long CRYO_FREEZE_TICKS = 60; // 3 s
    private static final long BODY_RUSH_TICKS = 200; // 10 s
    private static final long BODY_RUSH_CONTACT_PENALTY_TICKS = 40; // 2 s

    public void initialise(AbilityState state, Kit kit) {
        state.charges = switch (kit) {
            case JUMPER -> 3;
            case SLOWBALLER -> 16;
            case BODY_BUILDER -> 0;
            case REPULSOR -> 3;
            case MAVERICK -> 0;
        };
        state.cooldownUntilTick = 0;
        state.activeUntilTick = 0;
        state.activations = kit == Kit.BODY_BUILDER ? 2 : 0;
    }

    public void initialiseForMode(GameState game) {
        initialise(game.ability, game.kit);
        if (game.kit == Kit.JUMPER && game.mode == Mode.ORIGINAL) {
            game.ability.charges = 5;
        }
        game.player.jumpCharges = game.ability.charges;
    }

    public boolean qolEnabled(GameState game) {
        return game.mode != Mode.ORIGINAL;
    }

    /**
     * Monster Maze's Jumper resource is separate from Minecraft's jump physics.
     * The server checks once per tick while airborne and can consume a charge,
     * but never more often than once every 750 ms.
     */
    public boolean canConsumeJumperCharge(GameState game) {
        if (game.kit != Kit.JUMPER || game.ability.charges <= 0) return false;
        if (game.tick < game.player.nextJumpChargeTick) return false;
        if (game.tick < game.player.recentMobHitUntilTick) return false;
        if (qolEnabled(game) && isOnAnyPad(game)) return false;
        return true;
    }

    public boolean consumeJumperCharge(GameState game) {
        if (!canConsumeJumperCharge(game)) return false;
        game.ability.charges--;
        game.player.jumpCharges = game.ability.charges;
        game.player.nextJumpChargeTick = game.tick + JUMPER_RECHARGE_TICKS;
        return true;
    }

    public boolean activate(GameState game) {
        if (!game.alive) return false;
        return switch (game.kit) {
            case JUMPER -> false;
            case SLOWBALLER -> activateCryo(game);
            case BODY_BUILDER -> activateBodyRush(game);
            case REPULSOR -> activateRepulsor(game);
            case MAVERICK -> false;
        };
    }

    private boolean activateCryo(GameState game) {
        if (!qolEnabled(game) || game.tick < game.ability.cooldownUntilTick) return false;

        game.ability.cooldownUntilTick = game.tick + CRYO_COOLDOWN_TICKS;
        boolean froze = false;

        for (MonsterState m : game.monsters) {
            double dx = game.player.x - m.x;
            double dy = game.player.y - m.y;
            double dz = game.player.z - m.z;
            if (dx*dx + dy*dy + dz*dz <= 36.0) {
                m.frozenUntilTick = Math.max(m.frozenUntilTick, game.tick + CRYO_FREEZE_TICKS);
                m.vx = m.vy = m.vz = 0.0;
                froze = true;
            }
        }
        return froze;
    }

    private boolean activateBodyRush(GameState game) {
        if (!qolEnabled(game) || game.ability.activations <= 0
                || game.ability.activeUntilTick > game.tick) return false;

        game.ability.activations--;
        game.ability.activeUntilTick = game.tick + BODY_RUSH_TICKS;
        return true;
    }

    private boolean activateRepulsor(GameState game) {
        if (game.ability.charges <= 0) return false;
        game.ability.charges--;

        for (MonsterState m : game.monsters) {
            double dx = m.x - game.player.x;
            double dz = m.z - game.player.z;
            double distSq = dx*dx + dz*dz;
            if (distSq > 36.0) continue;

            double len = Math.sqrt(distSq);
            if (len < 1e-9) {
                dx = 1.0;
                dz = 0.0;
                len = 1.0;
            }
            dx /= len;
            dz /= len;

            m.vx = dx;
            m.vz = dz;
            m.vy = 0.8;
            m.launchedAtTick = game.tick;
            m.launchedUntilTick = game.tick + 30;
            m.waypointRow = -1;
            m.waypointColumn = -1;
        }
        return true;
    }

    /** Apply the kit's pad reward. The game layer supplies whether this player was first. */
    public void onReachedPad(GameState game, boolean first) {
        if (game.kit == Kit.JUMPER && qolEnabled(game)) {
            // Enhanced/QOL Jumper refills to the full three charges on a pad.
            game.ability.charges = 3;
            game.player.jumpCharges = 3;
        }
        if (game.kit == Kit.BODY_BUILDER && first) {
            game.player.maxHealth = Math.min(30.0, game.player.maxHealth + 2.0);
            game.player.health = Math.min(game.player.maxHealth, game.player.health + 4.0);
        } else if (first) {
            game.player.health = Math.min(game.player.maxHealth, game.player.health + 4.0);
        } else {
            game.player.health = Math.min(game.player.maxHealth, game.player.health + 2.0);
        }
    }

    public boolean isBodyRushActive(GameState game) {
        return game.kit == Kit.BODY_BUILDER
                && qolEnabled(game)
                && game.ability.activeUntilTick > game.tick;
    }

    public void consumeBodyRushContact(GameState game) {
        if (!isBodyRushActive(game)) return;
        game.ability.activeUntilTick = Math.max(
                game.tick,
                game.ability.activeUntilTick - BODY_RUSH_CONTACT_PENALTY_TICKS);
    }

    public boolean isOnAnyPad(GameState game) {
        return PadModel.isOn(game.player, game.activePadRow, 0, game.activePadColumn)
                || PadModel.isOn(game.player, game.previewPadRow, 0, game.previewPadColumn);
    }
}
