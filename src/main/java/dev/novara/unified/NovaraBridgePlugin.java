package dev.novara.unified;

import org.bukkit.Bukkit;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.server.*;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.lang.reflect.*;
import java.util.*;
import java.util.function.Consumer;

public final class NovaraBridgePlugin extends JavaPlugin implements Listener {
    private RankSync sync;
    private String rankStatus = "Not connected";
    private String syncStatus = "Not connected";
    @Override public void onEnable() {
        saveDefaultConfig();
        Bukkit.getPluginManager().registerEvents(this,this);
        connect();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (sync != null && ranksPlugin() != null) sync.tick();
        }, 20, Math.max(1, Math.min(300, getConfig().getInt("sync-interval-seconds",5))) * 20L);
    }
    @Override public void onDisable() { Bukkit.getServicesManager().unregisterAll(this); sync = null; }
    private boolean enabled(String integration) { return getConfig().getBoolean("integrations."+integration,true); }
    private Plugin active(String name) {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(name);
        return plugin != null && plugin.isEnabled() ? plugin : null;
    }
    private Plugin ranksPlugin() { return active("NovaraRanksTags"); }
    private void connect() {
        Bukkit.getServicesManager().unregisterAll(this);
        rankStatus = "Disabled";
        if (enabled("jobs-rank-benefits")) {
            if (active("NovaraRanksBridge") != null) rankStatus = "Conflict: remove old NovaraRanksBridge and restart";
            else if (active("NovaraJobs") == null) rankStatus = "Missing NovaraJobs";
            else try {
                requireRankApi();
                JobsRanks.register(this);
                rankStatus = "Ready";
            } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { rankStatus = e.getMessage(); }
        }
        sync = null;
        syncStatus = "Disabled";
        if (enabled("ranks-luckperms")) try {
            Plugin ranks = requireRankApi();
            if (active("LuckPerms") == null) throw new IllegalStateException("Missing LuckPerms");
            Map<Integer,String> groups = new HashMap<>();
            var section = getConfig().getConfigurationSection("rank-groups");
            if (section != null) for (String key : section.getKeys(false)) groups.put(Integer.parseInt(key),section.getString(key));
            Set<String> protectedGroups = new HashSet<>();
            for (String path : List.of("role-ranks","paid-ranks")) {
                var source = ((JavaPlugin)ranks).getConfig().getConfigurationSection(path);
                if (source != null) protectedGroups.addAll(source.getKeys(false));
            }
            sync = new RankSync(this,RankGroups.validate(groups,protectedGroups));
            syncStatus = "Ready";
        } catch (ReflectiveOperationException | RuntimeException | LinkageError e) { syncStatus = e.getMessage(); }
    }
    private Plugin requireRankApi() throws ReflectiveOperationException {
        Plugin ranks = ranksPlugin();
        if (ranks == null) throw new IllegalStateException("Missing NovaraRanksTags");
        ranks.getClass().getMethod("progressionRank",UUID.class);
        if (((JavaPlugin)ranks).getConfig().getBoolean("ranks-test-mode",true))
            throw new IllegalStateException("Disable ranks-test-mode in NovaraRanksTags and restart");
        return ranks;
    }
    /** Authoritative online progression rank. Missing/old rank plugins fail closed. */
    public int progressionRank(UUID id) {
        mainThread();
        try {
            Plugin ranks = requireRankApi();
            int rank = (Integer)ranks.getClass().getMethod("progressionRank",UUID.class).invoke(ranks,id);
            if (rank < 1 || rank > 10) throw new IllegalStateException("Invalid progression rank");
            return rank;
        } catch (ReflectiveOperationException e) { throw new IllegalStateException("Updated NovaraRanksTags required",e); }
    }
    public boolean rankBenefitsEnabled() { return enabled("jobs-rank-benefits") && "Ready".equals(rankStatus); }
    private void mainThread() { if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Bridge API requires server thread"); }
    private BridgeEconomy economy() {
        mainThread();
        if (!enabled("economy")) throw new IllegalStateException("Bridge economy integration disabled");
        return new BridgeEconomy();
    }
    public String economyStatus() {
        try { return economy().status(); }
        catch (RuntimeException | LinkageError e) { return "Unavailable: " + e.getMessage(); }
    }
    public double economyBalance(UUID id) { return economy().balance(id); }
    public boolean economyCredit(UUID id,long cents) { return economy().transfer(id,cents,true); }
    public boolean economyDebit(UUID id,long cents) { return economy().transfer(id,cents,false); }
    /** Keeps the existing read-only snapshot shape; callback thread belongs to Jobs. */
    public void astralLeaderboardSnapshot(Consumer<List<?>> callback) {
        mainThread();
        if (!enabled("jobs-leaderboard")) throw new IllegalStateException("Jobs leaderboard integration disabled");
        Plugin jobs = active("NovaraJobs");
        if (jobs == null) throw new IllegalStateException("Missing NovaraJobs");
        try { jobs.getClass().getMethod("astralLeaderboardSnapshot",Consumer.class).invoke(jobs,callback); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException("Jobs snapshot API unavailable",e); }
    }
    @EventHandler public void pluginEnabled(PluginEnableEvent event) { reconnectLater(event.getPlugin()); }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) { reconnectLater(event.getPlugin()); }
    private void reconnectLater(Plugin changed) {
        if (changed != this && Set.of("NovaraJobs","NovaraRanksTags","NovaraRanksBridge","LuckPerms").contains(changed.getName()))
            Bukkit.getScheduler().runTask(this,this::connect);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!sender.hasPermission("novarabridge.admin")) { sender.sendMessage("§cNo permission."); return true; }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig(); connect(); sender.sendMessage("§aBridge connections reloaded. Restart to apply sync interval changes.");
        } else if (args.length > 0 && !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("/novarabridge status | reload"); return true;
        }
        sender.sendMessage("§bNovaraBridge 0.1.0");
        sender.sendMessage("§7Jobs rank benefits: §f"+rankStatus);
        sender.sendMessage("§7LuckPerms rank sync: §f"+(sync == null ? syncStatus : sync.status()));
        sender.sendMessage("§7Economy: §f"+economyStatus());
        String jobsState = !enabled("jobs-leaderboard") ? "Disabled" : active("NovaraJobs") == null ? "Missing NovaraJobs" : "Ready";
        sender.sendMessage("§7Jobs snapshot: §f"+jobsState);
        sender.sendMessage("§7AstralLeaderboard: §f"+(active("AstralLeaderboard") == null ? "Not installed" : "Installed (updated build required)"));
        if (sender instanceof Player player) try { sender.sendMessage("§7Your progression rank: §f"+progressionRank(player.getUniqueId())); }
        catch (RuntimeException ignored) {}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if (!sender.hasPermission("novarabridge.admin") || args.length != 1) return List.of();
        return List.of("status","reload").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}
