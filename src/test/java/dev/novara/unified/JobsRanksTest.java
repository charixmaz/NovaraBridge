package dev.novara.unified;
import org.junit.jupiter.api.Test;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
class JobsRanksTest {
    public interface RankApi { int rankOf(UUID id); }
    public interface WrongApi { String rankOf(UUID id); }
    @Test void implementsInstalledInterfaceAndForwardsPlayerId() throws Exception {
        UUID id = UUID.randomUUID(); var seen = new AtomicReference<UUID>();
        RankApi provider = JobsRanks.provider(RankApi.class,value -> { seen.set(value); return 3; });
        assertEquals(3,provider.rankOf(id)); assertEquals(id,seen.get());
        assertEquals(provider,provider); assertNotEquals(provider,new Object());
        assertEquals(System.identityHashCode(provider),provider.hashCode());
    }
    @Test void rejectsIncompatibleApiBeforeRegistration() {
        assertThrows(IllegalArgumentException.class,() -> JobsRanks.provider(WrongApi.class,id -> 1));
    }
}
