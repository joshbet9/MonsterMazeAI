package me.monstermazeai.maze;

import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MazePathfinderTest {
    @Test void findsShortestCardinalPath() {
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=1;r<=3;r++) for(int c=1;c<=3;c++) raw[r][c]=1;
        MazeModel maze=new MazeModel(raw);
        List<Cell> path=new MazePathfinder().shortestPath(maze,new Cell(1,1),new Cell(3,3));
        assertEquals(5,path.size());
        assertEquals(new Cell(3,3),path.get(path.size()-1));
    }

    @Test void respectsDisabledCells() {
        int[][] raw=new int[MazeModel.SIZE][MazeModel.SIZE];
        for(int r=1;r<=3;r++) for(int c=1;c<=3;c++) raw[r][c]=1;
        MazeModel maze=new MazeModel(raw);
        maze.setDisabled(2,2,true);
        List<Cell> path=new MazePathfinder().shortestPath(maze,new Cell(1,1),new Cell(3,3));
        assertFalse(path.isEmpty());
        assertFalse(path.contains(new Cell(2,2)));
    }
}
