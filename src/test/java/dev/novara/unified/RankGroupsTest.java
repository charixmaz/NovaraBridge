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
    @Test void acceptsRanksSixAndSevenWithoutAllowingSourceFeedback() {
        var mapping=RankGroups.validate(Map.of(6,"novarabridge_rank_6",7,"novarabridge_rank_7"),Set.of("rank6","vip"));
        assertEquals("novarabridge_rank_7",mapping.get(7));
        assertThrows(IllegalArgumentException.class,()->RankGroups.validate(mapping,Set.of("novarabridge_rank_6")));
    }
    @Test void newDefaultsPreserveExistingOwnerConfiguration() throws Exception {
        var defaults=new org.bukkit.configuration.file.YamlConfiguration();
        try(var stream=getClass().getResourceAsStream("/config.yml")){
            defaults.load(new java.io.InputStreamReader(java.util.Objects.requireNonNull(stream),java.nio.charset.StandardCharsets.UTF_8));
        }
        var config=new org.bukkit.configuration.file.YamlConfiguration();
        config.set("integrations.economy",false);config.set("rank-groups.6","custom_rank_six");
        config.setDefaults(defaults);config.options().copyDefaults(true);
        var restored=new org.bukkit.configuration.file.YamlConfiguration();restored.loadFromString(config.saveToString());
        assertFalse(restored.getBoolean("integrations.economy"));
        assertEquals("custom_rank_six",restored.getString("rank-groups.6"));
        assertEquals("novarabridge_rank_7",restored.getString("rank-groups.7"));
    }
}

