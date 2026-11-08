package dev.novara.unified;

import org.bukkit.Bukkit;
import org.bukkit.plugin.ServicePriority;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.function.ToIntFunction;

/** Isolated so NovaraBridge can still start without NovaraJobs. */
final class JobsRanks {
    @SuppressWarnings({"rawtypes", "unchecked"})
    static void register(NovaraBridgePlugin plugin) throws ReflectiveOperationException {
        var jobs = Bukkit.getPluginManager().getPlugin("NovaraJobs");
        if (jobs == null || !jobs.isEnabled()) throw new IllegalStateException("Missing NovaraJobs");
        Class api = Class.forName("dev.novara.jobs.bridge.NovaraRankProvider",true,jobs.getClass().getClassLoader());
        Bukkit.getServicesManager().register(api,provider(api,plugin::progressionRank),plugin,ServicePriority.High);
    }
    static <T> T provider(Class<T> api, ToIntFunction<UUID> ranks) throws ReflectiveOperationException {
        if (!api.isInterface() || api.getMethod("rankOf",UUID.class).getReturnType() != int.class)
            throw new IllegalArgumentException("Unsupported Jobs rank interface");
        return api.cast(Proxy.newProxyInstance(api.getClassLoader(),new Class<?>[]{api},(proxy,method,args) -> {
            if (method.getDeclaringClass() == Object.class) return switch(method.getName()) {
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                case "toString" -> "NovaraBridge rank provider";
                default -> throw new UnsupportedOperationException(method.getName());
            };
            if (method.getName().equals("rankOf") && args != null && args.length == 1 && args[0] instanceof UUID id)
                return ranks.applyAsInt(id);
            throw new UnsupportedOperationException(method.getName());
        }));
    }
}

