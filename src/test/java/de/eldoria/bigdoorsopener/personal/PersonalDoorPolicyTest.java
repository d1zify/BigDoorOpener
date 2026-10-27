/*
 *     SPDX-License-Identifier: AGPL-3.0-only
 *
 *     Copyright (C) 2021 EldoriaRPG Team and Contributor
 */

package de.eldoria.bigdoorsopener.personal;

import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PersonalDoorPolicyTest {
    @Test void downwardAnimationMirrorsUpwardAndClosesAtOrigin() {
        for (int step = 0; step <= 20; step++) {
            assertEquals(-DoorDirection.UP.offset(4, step), DoorDirection.DOWN.offset(4, step), .0001f);
        }
        assertEquals(0f, DoorDirection.DOWN.offset(4, 0));
        assertEquals(-5f, DoorDirection.DOWN.offset(4, 20));
        assertEquals(DoorDirection.DOWN, DoorDirection.parse("baixo"));
        assertEquals(DoorDirection.DOWN, DoorDirection.parse("DOWN"));
        assertEquals(DoorDirection.UP, DoorDirection.parse("cima"));
        assertThrows(IllegalArgumentException.class, () -> DoorDirection.parse("left"));
    }
    private final UUID friend = UUID.randomUUID(), other = UUID.randomUUID();
    private final DoorVoxel wall = new DoorVoxel(0, 64, 0, true, "minecraft:oak_planks");
    @Test void permissionIsIndividual() {
        DoorAccessRule rule = new DoorAccessRule("kingdom.door.test", Set.of());
        assertTrue(rule.allows(friend, p -> p.equals("kingdom.door.test")));
        assertFalse(rule.allows(other, p -> false));
    }
    @Test void questPermissionChangesAccessWithoutReplacingRule() {
        DoorAccessRule rule = new DoorAccessRule("kingdom.quest.boss", Set.of());
        java.util.Set<String> granted = new java.util.HashSet<>();
        assertFalse(rule.allows(friend, granted::contains));
        granted.add("kingdom.quest.boss");
        assertTrue(rule.allows(friend, granted::contains));
        assertFalse(rule.allows(other, permission -> false));
        granted.clear();
        assertFalse(rule.allows(friend, granted::contains));
    }
    @Test void explicitPlayerDoesNotAdmitFriend() {
        DoorAccessRule rule = new DoorAccessRule("", Set.of(friend));
        assertTrue(rule.allows(friend, p -> false));
        assertFalse(rule.allows(other, p -> true));
    }
    @Test void emptyRuleDeniesEveryoneIncludingOp() {
        assertFalse(new DoorAccessRule("", Set.of()).allows(friend, p -> true));
    }
    @Test void snapshotRoundTripsBlockStates() {
        DoorVoxel door = new DoorVoxel(-3, -55, 4, true, "minecraft:oak_fence[east=true,north=false,south=true,waterlogged=false,west=false]");
        assertEquals(door, DoorVoxel.decode(door.encode()));
        assertThrows(IllegalArgumentException.class, () -> DoorVoxel.decode("bad"));
        assertThrows(IllegalArgumentException.class, () -> DoorVoxel.decode("1;2;3;maybe;minecraft:stone"));
    }
    @Test void catchesFastCrossingsAndBodyEdges() {
        assertTrue(wall.crosses(-3,64,.5,3,64,.5,.3,1.8));
        assertTrue(wall.crosses(-1,64,-.2,2,64,-.2,.3,1.8));
        assertFalse(wall.crosses(-1,64,-.4,2,64,-.4,.3,1.8));
    }
    @Test void leavesHolesAndSpaceAbovePassable() {
        assertFalse(wall.crosses(-3,65.1,.5,3,65.1,.5,.3,1.8));
        assertFalse(new DoorVoxel(0,64,0,false,"minecraft:air").crosses(-1,64,.5,2,64,.5,.3,1.8));
    }
    @Test void catchesStationaryBodyButNotFloorContact() {
        assertTrue(wall.crosses(.5,64,.5,.5,64,.5,.3,1.8));
        assertFalse(wall.crosses(.5,65,.5,.5,65,.5,.3,1.8));
    }
}
