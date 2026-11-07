package dev.novara.unified;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class RankGroupsTest {
    @Test void protectsPaidAndSourceGroups() {
        for (String group : List.of("vip","rank2","default","Bad Group"))
            assertThrows(IllegalArgumentException.class,() -> RankGroups.validate(Map.of(2,group),Set.of("vip","rank2")));
    }
    @Test void refusesDuplicatesAndInvalidRanks() {
        assertThrows(IllegalArgumentException.class,() -> RankGroups.validate(Map.of(1,"bridge1",2,"bridge1"),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> RankGroups.validate(Map.of(11,"bridge11"),Set.of()));
        assertThrows(IllegalArgumentException.class,() -> RankGroups.validate(Map.of(),Set.of()));
    }
    @Test void acceptsDedicatedGroupsAndReturnsImmutableMapping() {
        var mapping = RankGroups.validate(Map.of(1,"novarabridge_rank_1",2,"novarabridge_rank_2"),Set.of("vip","rank2"));
        assertEquals("novarabridge_rank_2",mapping.get(2));
        assertThrows(UnsupportedOperationException.class,() -> mapping.put(3,"other"));
    }
}
