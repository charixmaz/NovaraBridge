package dev.novara.unified;

import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneRewardConfigTest {
    private static MilestoneRewardConfig parse(String yaml){
        var y=new YamlConfiguration();y.loadFromString(yaml);return MilestoneRewardConfig.parse(y);
    }
    @Test void parsesJobAndRankPlans(){
        var settings=parse("""
            jobs:
              MINER:
                '30':
                  tags: [miner]
                  pouches:
                    - rarity: uncommon
                      quantity: 1
                  money: 2.50
                  items:
                    - material: DIAMOND
                      amount: 2
                  permissions: [starfall.jobs.bonus]
                  commands: ['say {player} got a reward']
            ranks:
              '2':
                pouches:
                  - rarity: common
                    quantity: 1
            """);
        assertEquals(java.util.List.of("tag:miner","pouch:uncommon:1","cash:250",
            "item:DIAMOND:2","permission:starfall.jobs.bonus",
            "command:say {player} got a reward"),settings.rule("job:MINER:30").actions());
        assertEquals(java.util.List.of("pouch:common:1"),settings.rule("rank:2").actions());
        assertEquals(6,settings.description("job:MINER:30").size());
    }
    @Test void invalidTagsTiersAndQuantitiesFailWithLocation(){
        assertTrue(assertThrows(IllegalArgumentException.class,()->
            parse("jobs:\n  MINER:\n    '30':\n      tags: ['Bad Tag']")).getMessage().contains("Invalid tag"));
        assertTrue(assertThrows(IllegalArgumentException.class,()->
            parse("ranks:\n  '2':\n    pouches:\n      - rarity: mythic\n        quantity: 1"))
            .getMessage().contains("Invalid pouch"));
        assertThrows(IllegalArgumentException.class,()->
            parse("ranks:\n  '2':\n    pouches:\n      - rarity: common\n        quantity: 0"));
        assertThrows(IllegalArgumentException.class,()->
            parse("ranks:\n  '20':\n    tags: [miner]"));
    }
}
