package me.monstermazeai.physics;

import me.monstermazeai.maze.MazeModel;
import me.monstermazeai.player.PlayerState;

import java.util.ArrayList;
import java.util.List;

/**
 * Legacy block-grid collision utility retained for future experiments.
 * It is deliberately not used by LegacyMazePhysics: Monster Maze player
 * movement is modeled as flat open movement with no walls or step-up logic.
 */
public final class MazeCollision {
    public static final double PLAYER_WIDTH = 0.6;
    public static final double PLAYER_HEIGHT = 1.8;
    public static final double STEP_HEIGHT = 0.6;

    private final MazeModel maze;

    public MazeCollision(MazeModel maze){this.maze=maze;}

    public Aabb playerBox(PlayerState p){
        double half=PLAYER_WIDTH/2.0;
        return new Aabb(p.x-half,p.y,p.z-half,p.x+half,p.y+PLAYER_HEIGHT,p.z+half);
    }

    public void move(PlayerState p,double dx,double dy,double dz){
        Aabb original=playerBox(p);
        List<Aabb> boxes=colliders(original.expand(Math.abs(dx),Math.abs(dy),Math.abs(dz)));

        double clippedY=dy;
        for(Aabb b:boxes) clippedY=b.clipY(original,clippedY);
        Aabb afterY=original.offset(0,clippedY,0);

        double clippedX=dx;
        for(Aabb b:boxes) clippedX=b.clipX(afterY,clippedX);
        Aabb afterX=afterY.offset(clippedX,0,0);

        double clippedZ=dz;
        for(Aabb b:boxes) clippedZ=b.clipZ(afterX,clippedZ);

        boolean horizontalBlocked = clippedX != dx || clippedZ != dz;
        double directDistanceSq = clippedX*clippedX + clippedZ*clippedZ;

        if (horizontalBlocked && p.grounded) {
            Aabb stepBase = original;
            List<Aabb> stepBoxes = colliders(stepBase.expand(Math.abs(dx), STEP_HEIGHT + Math.abs(dy), Math.abs(dz)));

            double sy = STEP_HEIGHT;
            for (Aabb b:stepBoxes) sy=b.clipY(stepBase,sy);
            Aabb stepY=stepBase.offset(0,sy,0);

            double sx=dx;
            for(Aabb b:stepBoxes)sx=b.clipX(stepY,sx);
            Aabb stepX=stepY.offset(sx,0,0);

            double sz=dz;
            for(Aabb b:stepBoxes)sz=b.clipZ(stepX,sz);

            Aabb stepFinal=stepX.offset(0,0,sz);
            double stepDistanceSq=sx*sx+sz*sz;

            if(stepDistanceSq > directDistanceSq) {
                clippedX=sx;
                clippedZ=sz;
                clippedY=sy;
                p.x=(stepFinal.minX() + stepFinal.maxX())/2.0;
                p.y=stepFinal.minY();
                p.z=(stepFinal.minZ() + stepFinal.maxZ())/2.0;
                p.grounded=true;
                if (dy < 0 || sy != dy) p.vy=0;
                if (clippedX != dx) p.vx=0;
                if (clippedZ != dz) p.vz=0;
                return;
            }
        }

        p.x += clippedX;
        p.y += clippedY;
        p.z += clippedZ;
        p.grounded = dy < 0 && clippedY != dy;

        if(clippedX!=dx)p.vx=0;
        if(clippedY!=dy)p.vy=0;
        if(clippedZ!=dz)p.vz=0;
    }

    private List<Aabb> colliders(Aabb swept){
        List<Aabb> out=new ArrayList<>();
        int minX=(int)Math.floor(swept.minX())-1,maxX=(int)Math.floor(swept.maxX())+1;
        int minY=(int)Math.floor(swept.minY())-1,maxY=(int)Math.floor(swept.maxY())+1;
        int minZ=(int)Math.floor(swept.minZ())-1,maxZ=(int)Math.floor(swept.maxZ())+1;
        for(int r=minX;r<=maxX;r++)for(int c=minZ;c<=maxZ;c++){
            if(r<0||c<0||r>=MazeModel.SIZE||c>=MazeModel.SIZE)continue;
            if(!maze.isTraversable(r,c))out.add(new Aabb(r,0,c,r+1,3,c+1));
        }
        return out;
    }
}