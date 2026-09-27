package me.monstermazeai.validation;
import me.monstermazeai.ability.AbilityModel;
import me.monstermazeai.game.GameState;
import me.monstermazeai.game.Mode;
import me.monstermazeai.kit.Kit;
import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.monster.MonsterSimulator;
import me.monstermazeai.monster.MonsterState;
import me.monstermazeai.physics.LegacyMazePhysics;
import me.monstermazeai.physics.MonsterMazeBumpModel;
import me.monstermazeai.sim.Simulator;
import me.monstermazeai.player.Action;
import org.junit.jupiter.api.Test;
import java.util.Random;
import static org.junit.jupiter.api.Assertions.*;
class CollisionPipelineDiagnosticTest {
 @Test void diagnostic() {
  int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
  for(int r=0;r<MazeModel.SIZE;r++) for(int c=0;c<MazeModel.SIZE;c++) raw[r][c]=1;
  GameState s=new GameState(); s.mode=Mode.MODERN; s.maze=new MazeModel(raw); s.kit=Kit.JUMPER;
  s.player.x=10.5; s.player.y=0; s.player.z=10.5; s.player.grounded=true; new AbilityModel().initialiseForMode(s);
  MonsterState m=new MonsterState(1,10.5,0,10.5); s.monsters.add(m);
  GameState direct=s.copy();
  int dh=MonsterMazeBumpModel.apply(direct);
  System.out.println("DIAG direct="+dh+" hp="+direct.player.health+" vx="+direct.player.vx+" vy="+direct.player.vy);
  Simulator sim=new Simulator(new LegacyMazePhysics(),new MonsterSimulator(s.maze,new Random(7),0.0),new me.monstermazeai.collision.CollisionModel());
  sim.tick(s,Action.IDLE);
  System.out.println("DIAG sim hp="+s.player.health+" vx="+s.player.vx+" vy="+s.player.vy+" tick="+s.tick);
  assertEquals(1,dh); assertEquals(16.0,direct.player.health,1e-9); assertEquals(16.0,s.player.health,1e-9);
 }
}
