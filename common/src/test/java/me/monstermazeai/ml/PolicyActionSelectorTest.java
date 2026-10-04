package me.monstermazeai.ml;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PolicyActionSelectorTest {

    @Test
    void counterfactualCandidateSetContainsMeaningfulAlternatives() {
        GameState state = new GameState();
        Action baseline = new Action(1.0, 0.0, false, true, 0.0F, false);

        List<Action> candidates =
                PolicyActionSelector.candidates(baseline, true, state);

        assertTrue(candidates.size() >= 15);
        assertTrue(candidates.contains(baseline));

        Set<Action> unique = new HashSet<>(candidates);
        assertTrue(unique.size() == candidates.size());

        assertFalse(candidates.stream().allMatch(baseline::equals));
        assertTrue(candidates.stream().anyMatch(a -> a.jump()));
        assertTrue(candidates.stream().anyMatch(a -> a.strafe() > 0.5));
        assertTrue(candidates.stream().anyMatch(a -> a.strafe() < -0.5));
        assertTrue(candidates.stream().anyMatch(a -> a.yawDelta() != 0.0F));
    }
}
