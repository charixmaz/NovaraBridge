package dev.novara.unified;

import org.junit.jupiter.api.*;
import org.bukkit.*;
import org.bukkit.plugin.*;
import net.milkbowl.vault.economy.*;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class BridgeEconomyTest {
    static final SimpleServicesManager services = new SimpleServicesManager();
    static final AtomicInteger deposits = new AtomicInteger(), withdrawals = new AtomicInteger();
    static final AtomicReference<Double> balance = new AtomicReference<>(100.0);
    static final AtomicBoolean success = new AtomicBoolean(true), enabled = new AtomicBoolean(true);
    static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},(p,m,a) -> {
            if (m.getName().equals("hashCode")) return System.identityHashCode(p);
            if (m.getName().equals("equals")) return p == a[0];
            if (m.getName().equals("toString")) return type.getSimpleName();
            return handler.invoke(p,m,a);
        }));
    }
    static final Plugin essentials = proxy(Plugin.class,(p,m,a) -> switch(m.getName()) {
        case "getName" -> "Essentials"; case "isEnabled" -> enabled.get(); default -> null;
    });
    static final Economy economy = proxy(Economy.class,(p,m,a) -> switch(m.getName()) {
        case "getName" -> "Essentials Economy";
        case "getBalance" -> balance.get();
        case "has" -> balance.get() >= (Double)a[1];
        case "depositPlayer", "withdrawPlayer" -> {
            if (m.getName().equals("depositPlayer")) deposits.incrementAndGet(); else withdrawals.incrementAndGet();
            yield new EconomyResponse((Double)a[1],balance.get(),success.get() ? EconomyResponse.ResponseType.SUCCESS : EconomyResponse.ResponseType.FAILURE,"declined");
        }
        default -> null;
    });
    @BeforeAll static void server() throws Exception {
        OfflinePlayer offline = proxy(OfflinePlayer.class,(p,m,a) -> null);
        var manager = proxy(PluginManager.class,(p,m,a) -> null);
        var server = proxy(Server.class,(p,m,a) -> switch(m.getName()) {
            case "getServicesManager" -> services;
            case "getPluginManager" -> manager;
            case "isPrimaryThread" -> true;
            case "getOfflinePlayer" -> offline;
            case "getLogger" -> java.util.logging.Logger.getLogger("bridge-test");
            case "getName", "getVersion", "getBukkitVersion" -> "test";
            default -> null;
        });
        var field = Bukkit.class.getDeclaredField("server");
        field.setAccessible(true); field.set(null,server);
    }
    @BeforeEach void reset() {
        services.unregisterAll(essentials); deposits.set(0); withdrawals.set(0);
        balance.set(100.0); success.set(true); enabled.set(true);
        services.register(Economy.class,economy,essentials,ServicePriority.Normal);
    }
    @Test void moneyUsesOneProviderCallPerAttempt() {
        var adapter = new BridgeEconomy(); UUID id = UUID.randomUUID();
        assertTrue(adapter.transfer(id,15000,true)); assertEquals(1,deposits.get());
        assertFalse(adapter.transfer(id,15000,false)); assertEquals(0,withdrawals.get());
        success.set(false); assertFalse(adapter.transfer(id,1000,true)); assertEquals(2,deposits.get());
    }
    @Test void rejectsBadAmountsBeforeAnyMutation() {
        for (long amount : new long[]{0,-1,Long.MAX_VALUE})
            assertThrows(IllegalArgumentException.class,() -> new BridgeEconomy().transfer(UUID.randomUUID(),amount,true));
        assertEquals(0,deposits.get());
    }
    @Test void detectsProviderRemovalAndInvalidBalances() {
        var adapter = new BridgeEconomy(); assertEquals(100,adapter.balance(UUID.randomUUID()));
        balance.set(Double.NaN); assertThrows(IllegalStateException.class,() -> adapter.balance(UUID.randomUUID()));
        enabled.set(false); assertThrows(IllegalStateException.class,adapter::status);
        enabled.set(true); services.unregisterAll(essentials); assertThrows(IllegalStateException.class,adapter::status);
    }
}
