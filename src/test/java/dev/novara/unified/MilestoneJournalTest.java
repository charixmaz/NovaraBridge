package dev.novara.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class MilestoneJournalTest {
    @TempDir Path folder;
    @Test void questReceiptIsImmutableAndSurvivesRestart()throws Exception{
        Path path=folder.resolve("quest-journal.properties");
        UUID player=UUID.randomUUID();
        String operation="quest:"+player+":hunt_villagers:2026-11-09";
        var journal=new RewardJournal(path);
        journal.queueMilestone(operation,player,List.of("tag:hunter","pouch:uncommon:1"));
        journal.markStep(operation,0,"READY","IN_FLIGHT");
        journal.markStep(operation,0,"IN_FLIGHT","DONE");
        var restarted=new RewardJournal(path);
        restarted.queueMilestone(operation,player,List.of("tag:adventurer"));
        var resume=restarted.pendingMilestones(player).getFirst();
        assertEquals(2,resume.steps().size());
        assertEquals("tag:hunter",resume.steps().getFirst());
        assertEquals(java.util.List.of("DONE","READY"),resume.states());
    }
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
    @Test void twentyPaidContractsUnlockExactlyOneContractorTagAcrossRestarts()throws Exception{
        Path path=folder.resolve("contractor.properties");
        UUID worker=UUID.randomUUID();
        String operation="contract:"+worker+":20";
        var journal=new RewardJournal(path);
        journal.queueMilestone(operation,worker,List.of("tag:contractor"));
        // Repeated 20th/21st completion cannot change a frozen reward.
        journal.queueMilestone(operation,worker,List.of("tag:founder"));
        var claim=journal.pendingMilestones(worker).getFirst();
        assertEquals(List.of("tag:contractor"),claim.steps());
        journal.markStep(operation,0,"READY","IN_FLIGHT");
        journal.markStep(operation,0,"IN_FLIGHT","DONE");
        var restarted=new RewardJournal(path);
        restarted.queueMilestone(operation,worker,List.of("tag:contractor"));
        assertTrue(restarted.pendingMilestones(worker).isEmpty());
        assertThrows(IllegalStateException.class,()->
            restarted.markStep(operation,0,"READY","IN_FLIGHT"));
    }

}
