package me.monstermazeai.physics;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AabbTest {
    @Test void clipsMovementAgainstPositiveXFace() {
        Aabb wall=new Aabb(2,0,0,3,3,1);
        Aabb player=new Aabb(0.4,0,0.2,1.0,1.8,0.8);
        assertEquals(1.0,wall.clipX(player,2.0),1e-9);
    }

    @Test void leavesNonOverlappingAxisFree() {
        Aabb wall=new Aabb(2,0,0,3,3,1);
        Aabb player=new Aabb(0.4,0,2,1.0,1.8,2.6);
        assertEquals(2.0,wall.clipX(player,2.0),1e-9);
    }
}
