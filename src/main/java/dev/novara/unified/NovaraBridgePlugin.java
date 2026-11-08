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
    private RewardJournal rewardJournal;
    private MilestoneDelivery milestoneDelivery;
    private String rankStatus = "Not connected";
    private String syncStatus = "Not connected";
    @Override public void onEnable() {
        try { rewardJournal = new RewardJournal(getDataFolder().toPath().resolve("reward-journal.properties")); }
        catch (java.io.IOException error) { throw new IllegalStateException("Cannot load reward receipts; refusing payouts",error); }
        saveDefaultConfig();
        try{milestoneDelivery=new MilestoneDelivery(this,rewardJournal);}
        catch(Exception error){throw new IllegalStateException("Cannot parse rewards.yml: "+error.getMessage(),error);}
        try{milestoneDelivery.reload();}
        catch(Exception invalid){
            // A missing tag on an older server config must not take down the
            // shared Vault/economy bridge. The reward remains waiting.
            getLogger().severe("Reward configuration needs attention: "+invalid.getMessage()+
                " (bridge economy and rank sync remain active)");
        }
        getConfig().options().copyDefaults(true);
        saveConfig();
        Bukkit.getPluginManager().registerEvents(this,this);
        connect();
        Bukkit.getScheduler().runTaskTimer(this, () -> {
            if (sync != null && ranksPlugin() != null) sync.tick();
        }, 20, Math.max(1, Math.min(300, getConfig().getInt("sync-interval-seconds",5))) * 20L);
        Bukkit.getScheduler().runTaskTimer(this,()->{
            for(Player online:Bukkit.getOnlinePlayers())milestoneDelivery.pump(online.getUniqueId());
        },120L,1200L);
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
    /** Stored reward plan is owned by NovaraBridge, not copied to each plugin. */
    public boolean claimJobMilestone(UUID player,String job,int level){
        mainThread();
        if(player==null||job==null||!job.matches("[A-Z_]+")||level<1||level>50)
            throw new IllegalArgumentException("Invalid job milestone");
        return milestoneDelivery.claimJob(player,job,level);
    }
    public boolean claimRankUpgrade(UUID player,int rank){
        mainThread();
        if(player==null||rank<2||rank>10)throw new IllegalArgumentException("Invalid rank reward");
        return milestoneDelivery.claimRank(player,rank);
    }
    public java.util.List<String> rewardDescriptions(String rule){
        mainThread();return milestoneDelivery.describe(rule);
    }
    public java.util.Map<Integer,java.util.List<String>> jobRewardSummaries(String job){
        mainThread();return milestoneDelivery.jobSummaries(job);
    }
    /** Checks tags/pouch tiers against the active source plugins and shows missing dependencies. */
    public java.util.List<String> rewardProblems(){
        mainThread();return milestoneDelivery.warnings();
    }

    /** Astral owns reward configuration. Operation must remain season/category/place stable. */
    public boolean astralReward(UUID player, String operation, long cents, List<String> commands) {
        mainThread();
        if (!enabled("astral-rewards")) throw new IllegalStateException("Astral rewards disabled in bridge");
        if (player == null || operation == null || !operation.matches("astral:[0-9]+:[a-z]+:[0-9]+") || cents < 0 || cents > 100_000_000L)
            throw new IllegalArgumentException("Invalid Astral reward request");
        List<String> plan = new ArrayList<>();
        if (cents > 0) {
            if (!economyStatus().startsWith("Ready:")) throw new IllegalStateException(economyStatus());
            plan.add("cash:"+cents);
        }
        String name = Bukkit.getOfflinePlayer(player).getName();
        for (String template : List.copyOf(commands)) {
            if (template == null || template.isBlank() || template.contains("\n") || template.contains("\r") || template.startsWith("/"))
                throw new IllegalArgumentException("Reward commands must be nonempty single lines without leading slash");
            if (template.contains("{player}") && (name == null || !name.matches("[A-Za-z0-9_]{1,16}")))
                throw new IllegalStateException("Recipient name unavailable; retry once their name is known");
            String resolved=template.replace("{uuid}",player.toString()).replace("{player}",name == null ? "" : name);
            if (Bukkit.getCommandMap().getCommand(resolved.split(" ",2)[0]) == null)
                throw new IllegalStateException("Reward command unavailable: "+resolved.split(" ",2)[0]);
            plan.add("command:"+resolved);
        }
        if (plan.isEmpty()) throw new IllegalArgumentException("No reward actions configured");
        try {
            return rewardJournal.deliver(operation,player,plan,step -> step.startsWith("cash:")
                ? economyCredit(player,Long.parseLong(step.substring(5)))
                : Bukkit.dispatchCommand(Bukkit.getConsoleSender(),step.substring(8)));
        } catch (java.io.IOException error) { throw new IllegalStateException("Reward receipt write failed; inspect before retrying",error); }
    }
    @EventHandler public void onJoin(org.bukkit.event.player.PlayerJoinEvent event){
        Bukkit.getScheduler().runTaskLater(this,()->
            milestoneDelivery.pump(event.getPlayer().getUniqueId()),40L);
    }
    @EventHandler public void pluginEnabled(PluginEnableEvent event) { reconnectLater(event.getPlugin()); }
    @EventHandler public void pluginDisabled(PluginDisableEvent event) { reconnectLater(event.getPlugin()); }
    private void reconnectLater(Plugin changed) {
        if (changed != this && Set.of("NovaraJobs","NovaraRanksTags","NovaraRanksBridge","LuckPerms").contains(changed.getName()))
            Bukkit.getScheduler().runTask(this,this::connect);
    }
    @Override public boolean onCommand(CommandSender sender,Command command,String label,String[] args) {
        if (!sender.hasPermission("novarabridge.admin")) { sender.sendMessage("§cNo permission."); return true; }
        if(args.length>=1 && args[0].equalsIgnoreCase("rewards")){
            if(args.length==5&&args[1].equalsIgnoreCase("resolve")){
                try{
                    int index=Integer.parseInt(args[3]);
                    if(!Set.of("done","retry").contains(args[4].toLowerCase(Locale.ROOT)))
                        throw new IllegalArgumentException("Choose done or retry");
                    milestoneDelivery.resolve(args[2],index,args[4].equalsIgnoreCase("done"));
                    sender.sendMessage("§aReward review updated; verify the external transaction first.");
                }catch(Exception error){sender.sendMessage("§cCould not resolve: "+error.getMessage());}
                return true;
            }
            if(args.length==3&&args[1].equalsIgnoreCase("pending")){
                Player target=Bukkit.getPlayerExact(args[2]);
                if(target==null){sender.sendMessage("§cPlayer must be online.");return true;}
                var lines=milestoneDelivery.pendingDetails(target.getUniqueId());
                if(lines.isEmpty())sender.sendMessage("§aNo pending rewards for "+target.getName());
                for(String line:lines)sender.sendMessage("§e"+line);
                return true;
            }
            if(args.length==2&&args[1].equalsIgnoreCase("reload")){
                try{milestoneDelivery.reload();sender.sendMessage("§aNovaraBridge rewards.yml reloaded.");}
                catch(Exception invalid){sender.sendMessage("§cReward configuration rejected: "+invalid.getMessage());}
                return true;
            }
            sender.sendMessage("§bReward claims pending or requiring review: §f"+milestoneDelivery.pending());
            for(String note:milestoneDelivery.warnings())sender.sendMessage("§e"+note);
            sender.sendMessage("§7/nb rewards reload | pending <online-player> | resolve <operation> <index> done|retry");
            return true;
        }
        if (args.length == 1 && args[0].equalsIgnoreCase("reload")) {
            reloadConfig();getConfig().options().copyDefaults(true);saveConfig();connect();
            try{milestoneDelivery.reload();}
            catch(Exception invalid){sender.sendMessage("§cRewards kept previous settings: "+invalid.getMessage());}
            sender.sendMessage("§aBridge connections reloaded. Restart to apply sync interval changes.");
        } else if (args.length > 0 && !args[0].equalsIgnoreCase("status")) {
            sender.sendMessage("/novarabridge status | reload"); return true;
        }
        sender.sendMessage("§bNovaraBridge 0.1.1");
        sender.sendMessage("§7Jobs rank benefits: §f"+rankStatus);
        sender.sendMessage("§7LuckPerms rank sync: §f"+(sync == null ? syncStatus : sync.status()));
        sender.sendMessage("§7Economy: §f"+economyStatus());
        String jobsState = !enabled("jobs-leaderboard") ? "Disabled" : active("NovaraJobs") == null ? "Missing NovaraJobs" : "Ready";
        sender.sendMessage("§7Jobs snapshot: §f"+jobsState);
        sender.sendMessage("§7Astral rewards: §f"+(enabled("astral-rewards") ? "Ready (companion delivery command required)" : "Disabled"));
        sender.sendMessage("§7Milestone reward claims pending/review: §f"+milestoneDelivery.pending());
        sender.sendMessage("§7AstralLeaderboard: §f"+(active("AstralLeaderboard") == null ? "Not installed" : "Installed (updated build required)"));
        if (sender instanceof Player player) try { sender.sendMessage("§7Your progression rank: §f"+progressionRank(player.getUniqueId())); }
        catch (RuntimeException ignored) {}
        return true;
    }
    @Override public List<String> onTabComplete(CommandSender sender,Command command,String alias,String[] args) {
        if (!sender.hasPermission("novarabridge.admin") || args.length != 1) return List.of();
        return List.of("status","reload","rewards").stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).toList();
    }
}

