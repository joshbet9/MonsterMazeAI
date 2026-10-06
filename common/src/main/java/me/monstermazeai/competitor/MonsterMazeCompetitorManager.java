package me.monstermazeai.competitor;

import me.monstermazeai.game.GameState;
import me.monstermazeai.player.Action;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Manages multiple independent AI competitors in one Monster Maze match. */
public final class MonsterMazeCompetitorManager {
    private final Map<String, MonsterMazeCompetitor> competitors =
            new LinkedHashMap<String, MonsterMazeCompetitor>();

    public void register(CompetitorDefinition definition) {
        if (definition == null) throw new IllegalArgumentException("definition");
        if (competitors.containsKey(definition.id))
            throw new IllegalArgumentException("Duplicate competitor: " + definition.id);
        competitors.put(definition.id, new MonsterMazeCompetitor(definition));
    }

    public void unregister(String id) {
        MonsterMazeCompetitor c=competitors.remove(id);
        if (c != null) c.reset();
    }

    public Action decide(String id, GameState state) {
        MonsterMazeCompetitor c=competitors.get(id);
        return c == null ? Action.IDLE : c.decide(state);
    }

    public void resetAll() {
        for (MonsterMazeCompetitor c : competitors.values()) c.reset();
    }

    public Map<String, MonsterMazeCompetitor> competitors() {
        return Collections.unmodifiableMap(competitors);
    }

    public int size() { return competitors.size(); }
}
