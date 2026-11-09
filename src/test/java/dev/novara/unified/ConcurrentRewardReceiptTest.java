package dev.novara.unified;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

/** Fake external side effect only; never calls the Vault/Essentials provider. */
class ConcurrentRewardReceiptTest {
    @TempDir Path folder;
    @Test void identicalConcurrentDeliveriesInvokeEffectExactlyOnceAndRemainDoneAfterRestart()
        throws Exception {
        Path path=folder.resolve("reward-journal.properties");
        UUID player=UUID.randomUUID();
        var journal=new RewardJournal(path);
        var effects=new AtomicInteger();
        ExecutorService pool=Executors.newFixedThreadPool(8);
        CountDownLatch go=new CountDownLatch(1);
        List<Future<Boolean>> work=new ArrayList<>();
        try {
            for(int i=0;i<24;i++) {
                work.add(pool.submit(()->{
                    go.await();
                    return journal.deliver("staging-receipt-1",player,List.of("cash:100"),step->{
                        effects.incrementAndGet();
                        return true;
                    });
                }));
            }
            go.countDown();
            for(var f:work)assertTrue(f.get(10,TimeUnit.SECONDS));
            assertEquals(1,effects.get(),"One effect for one immutable journal operation");
            assertTrue(new RewardJournal(path).deliver("staging-receipt-1",
                player,List.of("cash:100"),step->{fail("must not credit again");return true;}));
            assertEquals(1,effects.get());
        }finally {pool.shutdownNow();}
    }

    @Test void pendingExternalEffectCannotBeReplayedAsNewReceipt() throws Exception {
        Path path=folder.resolve("ambiguous.properties");
        UUID player=UUID.randomUUID();
        var journal=new RewardJournal(path);
        var calls=new AtomicInteger();
        assertFalse(journal.deliver("staging-uncertain-1",player,List.of("cash:100"),
            step->{calls.incrementAndGet();return false;}));
        assertThrows(IllegalStateException.class,()->new RewardJournal(path)
            .deliver("staging-uncertain-1",player,List.of("cash:100"),
                step->{calls.incrementAndGet();return true;}));
        assertEquals(1,calls.get());
    }
}
