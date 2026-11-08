package dev.novara.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneJournalTest {
    @TempDir Path folder;
    @Test void onlyOneImmutableClaimAndPerStepReceiptAcrossRestart()throws Exception{
        Path path=folder.resolve("rewards.properties");
        UUID player=UUID.randomUUID();
        String op="job:"+player+":MINER:30";
        var journal=new RewardJournal(path);
        journal.queueMilestone(op,player,List.of("tag:miner","pouch:uncommon:1"));
        journal.queueMilestone(op,player,List.of("cash:99999")); // immutable original
        assertEquals(2,journal.pendingMilestones(player).getFirst().steps().size());
        journal.markStep(op,0,"READY","IN_FLIGHT");
        journal.markStep(op,0,"IN_FLIGHT","DONE");
        assertThrows(IllegalStateException.class,()->
            journal.markStep(op,0,"READY","IN_FLIGHT"));
        var resumed=new RewardJournal(path);
        var state=resumed.pendingMilestones(player).getFirst();
        assertEquals(List.of("DONE","READY"),state.states());
        resumed.markStep(op,1,"READY","IN_FLIGHT");
        var afterCrash=new RewardJournal(path);
        assertEquals("IN_FLIGHT",afterCrash.pendingMilestones(player).getFirst().states().get(1));
        assertEquals(1,afterCrash.unresolvedMilestones());
    }
}
