package dev.novara.unified;

import net.milkbowl.vault.economy.Economy;
import org.bukkit.Bukkit;
import java.util.UUID;

/** One provider lookup per operation; never caches a disabled economy plugin. */
final class BridgeEconomy {
    private Economy provider() {
        var registration = Bukkit.getServicesManager().getRegistration(Economy.class);
        if (registration == null || !registration.getPlugin().isEnabled())
            throw new IllegalStateException("No enabled Vault Economy provider");
        String owner = registration.getPlugin().getName();
        if (!owner.equalsIgnoreCase("Essentials") && !owner.equalsIgnoreCase("EssentialsX"))
            throw new IllegalStateException("Vault Economy must be provided by EssentialsX; found " + owner);
        return registration.getProvider();
    }
    String status() { return "Ready: " + provider().getName(); }
    double balance(UUID id) {
        double value = provider().getBalance(Bukkit.getOfflinePlayer(id));
        if (!Double.isFinite(value) || value < 0) throw new IllegalStateException("Invalid provider balance");
        return value;
    }
    boolean transfer(UUID id, long cents, boolean credit) {
        if (cents <= 0 || cents > 100_000_000L) throw new IllegalArgumentException("Amount must be 1..100000000 cents");
        var economy = provider();
        var player = Bukkit.getOfflinePlayer(id);
        double amount = cents / 100.0;
        if (!credit && !economy.has(player, amount)) return false;
        // Exactly one attempt. A failed/ambiguous call is never automatically replayed.
        var result = credit ? economy.depositPlayer(player, amount) : economy.withdrawPlayer(player, amount);
        return result != null && result.transactionSuccess();
    }
}

