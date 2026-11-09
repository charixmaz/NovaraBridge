package dev.novara.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class ArcaneTesterJournalAuditTest {
    @TempDir Path folder;
    @Test void scanCountsNormalAndInFlightStepsSeparately() throws Exception {
        Path file=folder.resolve("journal.properties");
        UUID player=UUID.randomUUID();
        String op="job:"+player+":MINER:30";
        var journal=new RewardJournal(file);
        journal.queueMilestone(op,player,List.of("tag:miner","pouch:uncommon:1"));
        var a=journal.arcaneTesterIntegrityAudit();
        assertEquals(0L,a.get("malformed_receipts"));
        assertEquals(1L,a.get("milestones"));
        assertEquals(2L,a.get("ready"));
        journal.markStep(op,0,"READY","IN_FLIGHT");
        var next=new RewardJournal(file).arcaneTesterIntegrityAudit();
        assertEquals(1L,next.get("in_flight"));
        assertEquals(1L,next.get("ready"));
    }

    @Test void parallelClaimsFreezeOnePlanAndSurviveRestart() throws Exception {
        Path file=folder.resolve("race.properties");
        UUID player=UUID.randomUUID();
        String op="rank:"+player+":2";
        var journal=new RewardJournal(file);
        ExecutorService pool=Executors.newFixedThreadPool(8);
        CountDownLatch start=new CountDownLatch(1);
        List<Future<?>> pending=new ArrayList<>();
        try {
            for(int i=0;i<8;i++){
                final String tier="pouch:"+i;
                pending.add(pool.submit(() -> {
                    start.await();
                    journal.queueMilestone(op,player,List.of(tier));
                    return null;
                }));
            }
            start.countDown();
            for(var item:pending)item.get(10,TimeUnit.SECONDS);
            var reload=new RewardJournal(file);
            assertEquals(1L,reload.arcaneTesterIntegrityAudit().get("milestones"));
            assertEquals(1L,reload.arcaneTesterIntegrityAudit().get("steps"));
            assertEquals(1,reload.pendingMilestones(player).size());
            assertEquals(1,reload.pendingMilestones(player).getFirst().steps().size());
        } finally { pool.shutdownNow(); }
    }

    @Test void tamperedFingerprintIsDetected() throws Exception {
        Path file=folder.resolve("bad.properties");
        UUID player=UUID.randomUUID();
        var journal=new RewardJournal(file);
        journal.queueMilestone("job:"+player+":HUNTER:30",player,List.of("tag:hunter"));
        Properties props=new Properties();
        try(var in=Files.newInputStream(file)){props.load(in);}
        String key=props.stringPropertyNames().stream().filter(x->x.endsWith(".signature")).findFirst().orElseThrow();
        props.setProperty(key,"tampered");
        try(var out=Files.newOutputStream(file)){props.store(out,"test");}
        assertEquals(1L,new RewardJournal(file).arcaneTesterIntegrityAudit().get("malformed_receipts"));
    }
}
