package me.monstermazeai.ability;
import me.monstermazeai.game.GameState;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterState;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AbilityDecisionTest {
    private static GameState state(){
        GameState s=new GameState();s.inMonsterMaze=true;s.alive=true;s.maze=openMaze();
        s.kit=Kit.REPULSOR;s.activePadRow=55;s.activePadColumn=50;s.stage=1;
        s.player.x=50.5;s.player.z=50.5;s.player.health=20;s.ability.charges=3;
        return s;
    }
    private static MazeModel openMaze(){
        int[][] raw=new int[99][99];for(int r=0;r<99;r++)for(int c=0;c<99;c++)raw[r][c]=1;
        return new MazeModel(raw);
    }
    @Test void chargeAbilityIsConservedWhenThereIsNoImmediateNeed(){
        GameState s=state();s.monsters.add(new MonsterState(1,60.5,50.5,0));
        assertFalse(AbilityDecision.shouldUse(s));
    }
}
