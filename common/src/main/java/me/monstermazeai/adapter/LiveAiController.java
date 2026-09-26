package me.monstermazeai.adapter;
import me.monstermazeai.game.GameState;
import me.monstermazeai.maze.Cell;
import me.monstermazeai.planner.MazeAwareRecedingHorizonController;
import me.monstermazeai.player.Action;

public final class LiveAiController {
    private final WorldAdapter adapter;
    private final MazeAwareRecedingHorizonController controller;
    private final int executionTicks;

    public LiveAiController(WorldAdapter adapter,MazeAwareRecedingHorizonController controller,int executionTicks){
        if(adapter==null||controller==null||executionTicks<1)throw new IllegalArgumentException();
        this.adapter=adapter;this.controller=controller;this.executionTicks=executionTicks;
    }
    public void tick(){
        GameState state=adapter.observe().state();
        if(!state.alive||state.activePadRow<0||state.activePadColumn<0){
            adapter.execute(Action.IDLE);return;
        }
        Action[] actions=controller.nextActions(state,new Cell(state.activePadRow,state.activePadColumn),true);
        adapter.execute(actions);
    }
}
