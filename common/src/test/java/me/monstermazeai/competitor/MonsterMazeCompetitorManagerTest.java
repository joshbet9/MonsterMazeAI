package me.monstermazeai.competitor;

import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.player.Action;
import me.monstermazeai.player.PlayerBehaviorTracker;
import me.monstermazeai.player.PlayerBehaviorProfile;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MonsterMazeCompetitorManagerTest {
    private static PlayerBehaviorProfile profile(double sprint,double strafe) {
        return new PlayerBehaviorProfile(20,.2,sprint,0,.2,1,0,0,1,strafe);
    }

    private static GameState liveState() {
        GameState s=new GameState();
        s.inMonsterMaze=true; s.alive=true; s.mazePattern=1; s.phaseTicksRemaining=100;
        int[][] raw=new int[99][99];
        for(int r=0;r<99;r++) for(int c=0;c<99;c++) raw[r][c]=1;
        s.maze=new me.monstermazeai.maze.MazeModel(raw);
        s.activePadRow=60; s.activePadColumn=50;
        s.player.x=50.5; s.player.z=50.5; s.player.grounded=true;
        return s;
    }

    @Test void supportsMultipleIndependentCompetitors() {
        MonsterMazeCompetitorManager m=new MonsterMazeCompetitorManager();
        m.register(new CompetitorDefinition("sprinter","Sprinter",Kit.JUMPER,profile(1,0),true));
        m.register(new CompetitorDefinition("strafer","Strafer",Kit.JUMPER,profile(0,1),true));
        Action a=m.decide("sprinter",liveState().copy());
        Action b=m.decide("strafer",liveState().copy());
        assertTrue(a.sprint());
        assertTrue(Math.abs(b.strafe())>0);
        assertEquals(2,m.size());
    }

    @Test void lifecycleAndMissingIdsAreSafe() {
        MonsterMazeCompetitorManager m=new MonsterMazeCompetitorManager();
        assertEquals(Action.IDLE,m.decide("missing",liveState()));
        m.register(new CompetitorDefinition("one","One",Kit.JUMPER,new PlayerBehaviorTracker().profile(),true));
        m.resetAll(); m.unregister("one");
        assertEquals(0,m.size());
    }

    @Test void duplicateIdsAreRejected() {
        MonsterMazeCompetitorManager m=new MonsterMazeCompetitorManager();
        CompetitorDefinition d=new CompetitorDefinition("one","One",Kit.JUMPER,new PlayerBehaviorTracker().profile(),true);
        m.register(d);
        assertThrows(IllegalArgumentException.class,()->m.register(d));
    }
}
