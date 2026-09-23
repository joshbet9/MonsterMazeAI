package me.monstermazeai.physics;

public record Aabb(double minX,double minY,double minZ,double maxX,double maxY,double maxZ) {
    public Aabb offset(double x,double y,double z){return new Aabb(minX+x,minY+y,minZ+z,maxX+x,maxY+y,maxZ+z);}
    public Aabb expand(double x,double y,double z){return new Aabb(minX-x,minY-y,minZ-z,maxX+x,maxY+y,maxZ+z);}
    public boolean overlaps(Aabb b){return b.maxX>minX&&b.minX<maxX&&b.maxY>minY&&b.minY<maxY&&b.maxZ>minZ&&b.minZ<maxZ;}
    public double clipX(Aabb moving,double dx){
        if(moving.maxY<=minY||moving.minY>=maxY||moving.maxZ<=minZ||moving.minZ>=maxZ)return dx;
        if(dx>0&&moving.maxX<=minX)return Math.min(dx,minX-moving.maxX);
        if(dx<0&&moving.minX>=maxX)return Math.max(dx,maxX-moving.minX);
        return dx;
    }
    public double clipY(Aabb moving,double dy){
        if(moving.maxX<=minX||moving.minX>=maxX||moving.maxZ<=minZ||moving.minZ>=maxZ)return dy;
        if(dy>0&&moving.maxY<=minY)return Math.min(dy,minY-moving.maxY);
        if(dy<0&&moving.minY>=maxY)return Math.max(dy,maxY-moving.minY);
        return dy;
    }
    public double clipZ(Aabb moving,double dz){
        if(moving.maxX<=minX||moving.minX>=maxX||moving.maxY<=minY||moving.minY>=maxY)return dz;
        if(dz>0&&moving.maxZ<=minZ)return Math.min(dz,minZ-moving.maxZ);
        if(dz<0&&moving.minZ>=maxZ)return Math.max(dz,maxZ-moving.maxZ);
        return dz;
    }
}