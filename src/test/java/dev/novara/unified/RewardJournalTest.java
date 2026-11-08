package dev.novara.unified;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
class RewardJournalTest {
    @TempDir Path directory;
    @Test void restartAndRepeatedDeliveryDoNotRepeatEffects() throws Exception {
        Path file=directory.resolve("receipts"); UUID player=UUID.randomUUID(); var calls=new AtomicInteger();
        assertTrue(new RewardJournal(file).deliver("season-1",player,List.of("cash","tag"),s->{calls.incrementAndGet();return true;}));
        assertTrue(new RewardJournal(file).deliver("season-1",player,List.of("cash","tag"),s->{calls.incrementAndGet();return true;}));
        assertEquals(2,calls.get());
        assertThrows(IllegalStateException.class,()->new RewardJournal(file).deliver("season-1",player,List.of("other"),s->true));
    }
    @Test void ambiguousFailureIsNotAutomaticallyReplayed() throws Exception {
        Path file=directory.resolve("receipts"); UUID player=UUID.randomUUID();
        assertFalse(new RewardJournal(file).deliver("season-2",player,List.of("cash"),s->false));
        assertThrows(IllegalStateException.class,()->new RewardJournal(file).deliver("season-2",player,List.of("cash"),s->{fail("must not retry");return true;}));
    }
}
