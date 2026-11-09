package dev.novara.unified;

import net.luckperms.api.LuckPermsProvider;
import net.luckperms.api.node.Node;
import org.bukkit.*;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Method;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Uses RewardJournal receipts. Missing plugins/full inventories leave steps
 * READY for safe retry; a crash after beginning an external side effect is
 * explicitly REVIEW-only and never automatically repeated.
 */
final class MilestoneDelivery {
    private final NovaraBridgePlugin bridge;
    private final RewardJournal journal;
    private MilestoneRewardConfig config;
    private final Set<UUID> busy=ConcurrentHashMap.newKeySet();

    MilestoneDelivery(NovaraBridgePlugin bridge,RewardJournal journal) throws Exception {
        this.bridge=bridge;this.journal=journal;
        File file=new File(bridge.getDataFolder(),"rewards.yml");
        if(!file.exists())bridge.saveResource("rewards.yml",false);
        config=MilestoneRewardConfig.load(file);
    }
    void reload() throws Exception {
        File file=new File(bridge.getDataFolder(),"rewards.yml");
        MilestoneRewardConfig candidate=MilestoneRewardConfig.load(file);
        // The first invalid rule rejects all changes; no partially updated config.
        for(String key:candidate.keys())for(String step:candidate.rule(key).actions()){
            if(step.startsWith("tag:")) {
                String tag=step.substring(4);
                Plugin ranks=active("NovaraRanksTags");
                if(ranks!=null&&!invokeBoolean(ranks,"rewardTagExists",
                        new Class<?>[]{String.class},tag))
                    throw new IllegalArgumentException("Invalid Novara Ranks & Tags tag ID '"+tag+"' at "+key);
            }else if(step.startsWith("pouch:")){
                String rarity=step.split(":")[1];
                Plugin pouches=active("NovaraPouches");
                if(pouches!=null&&!invokeBoolean(pouches,"rewardPouchExists",
                        new Class<?>[]{String.class},rarity))
                    throw new IllegalArgumentException("Invalid pouch rarity '"+rarity+"' in NovaraPouches at "+key);
            }
        }
        config=candidate;
    }
    List<String> describe(String id) {return config.description(id);}
    Map<Integer,List<String>> jobSummaries(String job){
        Map<Integer,List<String>> result=new TreeMap<>();
        if(job==null || !job.matches("[A-Z_]+"))return result;
        for(String key:config.keys())if(key.startsWith("job:"+job+":")){
            try{result.put(Integer.parseInt(key.substring(("job:"+job+":").length())),
                config.description(key));}
            catch(NumberFormatException ignored){}
        }
        return Collections.unmodifiableMap(result);
    }
    List<String> pendingDetails(UUID player){
        List<String> lines=new ArrayList<>();
        for(var claim:journal.pendingMilestones(player))
            lines.add(claim.operation()+" "+claim.states());
        return List.copyOf(lines);
    }
    void resolve(String operation,int index,boolean delivered)throws IOException{
        checkThread();
        journal.markStep(operation,index,"IN_FLIGHT",delivered?"DONE":"READY");
        if(!delivered){
            for(var player:Bukkit.getOnlinePlayers())pump(player.getUniqueId());
        }
    }
    Set<String> keys(){return config.keys();}
    List<String> warnings() {
        Set<String> notes=new LinkedHashSet<>();
        for(String key:config.keys())for(String step:config.rule(key).actions()){
            if(step.startsWith("tag:")){
                var ranks=active("NovaraRanksTags");
                if(ranks==null)notes.add("Missing NovaraRanksTags: tag grants wait");
                else if(!invokeBoolean(ranks,"rewardTagExists",new Class<?>[]{String.class},step.substring(4)))
                    notes.add("Invalid or missing NovaraRanksTags tag ID '"+step.substring(4)+"' at "+key);
            }
            if(step.startsWith("pouch:")){
                var pouch=active("NovaraPouches");
                if(pouch==null)notes.add("Missing NovaraPouches: pouch grants wait");
                else if(!invokeBoolean(pouch,"rewardPouchExists",new Class<?>[]{String.class},step.split(":")[1]))
                    notes.add("Invalid or missing NovaraPouches rarity '"+step.split(":")[1]+"' at "+key);
            }
            if(step.startsWith("cash:")&&!bridge.economyStatus().startsWith("Ready:"))
                notes.add("Economy unavailable: "+bridge.economyStatus());
            if(step.startsWith("permission:")&&active("LuckPerms")==null)
                notes.add("Missing LuckPerms: permission grants wait");
        }
        return List.copyOf(notes);
    }
    boolean claimJob(UUID id,String job,int level) {
        return enqueue(id,"job:"+job+":"+level,"job:"+id+":"+job+":"+level);
    }
    boolean claimRank(UUID id,int rank) {
        return enqueue(id,"rank:"+rank,"rank:"+id+":"+rank);
    }
    /** Single, persistent unlock at 20 successfully paid worker contracts.
     * Uses the same immutable receipt as job, rank and quest rewards.
     */
    boolean claimContract(UUID id,int completed,String snapshot){
        checkThread();
        if(id==null || completed!=20)throw new IllegalArgumentException(
            "Contractor milestone is 20 completed contracts");
        var rule=MilestoneRewardConfig.parseBonus(snapshot);
        String operation="contract:"+id+":20";
        try{
            journal.queueMilestone(operation,id,rule.actions());
            pump(id);
            return true;
        }catch(IOException | RuntimeException error){
            bridge.getLogger().warning("Cannot queue contractor reward "+operation+": "+error.getMessage());
            return false;
        }
    }
    boolean claimQuest(UUID id,String questId,String period,String snapshot){
        checkThread();
        if(id==null||questId==null||!questId.matches("[a-z0-9_-]{3,40}")
            ||period==null||!period.matches("[0-9]{4}-[0-9]{2}-[0-9]{2}"))
            throw new IllegalArgumentException("Invalid quest operation identity");
        // Unlike ordinary milestone YAML, this is the accepted quest's
        // immutable reward snapshot persisted in the Jobs SQLite outbox.
        var rule=MilestoneRewardConfig.parseBonus(snapshot);
        String operation="quest:"+id+":"+questId+":"+period;
        try{
            journal.queueMilestone(operation,id,rule.actions());
            pump(id);
            return true;
        }catch(IOException | RuntimeException error){
            bridge.getLogger().warning("Cannot queue quest "+operation+": "+error.getMessage());
            return false;
        }
    }
    private boolean enqueue(UUID player,String key,String operation) {
        checkThread();
        var rule=config.rule(key);
        // No configured prize: completed, not an orphaned deliverable.
        if(rule==null)return true;
        try {
            journal.queueMilestone(operation,player,rule.actions());
            pump(player); // Never call Vault, inventories or SQL on a work event directly.
            return true;
        }catch(IOException | RuntimeException error) {
            bridge.getLogger().warning("Failed to persist milestone "+operation+": "+error.getMessage());
            return false;
        }
    }
    void pump(UUID id) {
        checkThread();
        Player player=Bukkit.getPlayer(id);
        if(player==null||!player.isOnline()||!busy.add(id))return;
        try {
            for(var claim:journal.pendingMilestones(id)){
                boolean waiting=false;
                for(int i=0;i<claim.steps().size();i++){
                    String status=claim.states().get(i);
                    if(status.equals("DONE"))continue;
                    if(!status.equals("READY")){waiting=true;break;} // IN_FLIGHT = manual review
                    String action=claim.steps().get(i);
                    if(!preflight(player,action)){waiting=true;break;}
                    journal.markStep(claim.operation(),i,"READY","IN_FLIGHT");
                    boolean synchronous=true;
                    try {
                        if(action.startsWith("tag:")){
                            synchronous=false;
                            String tag=action.substring(4);int index=i;
                            invokeTagGrant(player,tag,claim.operation()+":"+i,ok->
                                finishAsync(claim.operation(),id,index,ok));
                        } else if(action.startsWith("pouch:")){
                            String[] parts=action.split(":");
                            Plugin target=active("NovaraPouches");
                            if(!invokeBoolean(target,"giveRewardPouches",
                                    new Class<?>[]{UUID.class,String.class,int.class,String.class},
                                    id,parts[1],Integer.parseInt(parts[2]),claim.operation()+":"+i))
                                throw new IllegalStateException("Pouch delivery not confirmed");
                        } else if(action.startsWith("item:")){
                            String[] parts=action.split(":");
                            var item=new ItemStack(Objects.requireNonNull(Material.matchMaterial(parts[1])),
                                Integer.parseInt(parts[2]));
                            if(!player.getInventory().addItem(item).isEmpty())
                                throw new IllegalStateException("Inventory changed during delivery");
                        } else if(action.startsWith("cash:")){
                            if(!bridge.economyCredit(id,Long.parseLong(action.substring(5))))
                                throw new IllegalStateException("Vault credit not confirmed");
                        } else if(action.startsWith("permission:")){
                            synchronous=false;int index=i;
                            grantPermission(id,action.substring(11),ok->finishAsync(claim.operation(),id,index,ok));
                        } else if(action.startsWith("command:")){
                            String name=player.getName();
                            String command=action.substring(8).replace("{uuid}",id.toString()).replace("{player}",name);
                            if(!Bukkit.dispatchCommand(Bukkit.getConsoleSender(),command))
                                throw new IllegalStateException("Console command failed or was not confirmed");
                        } else throw new IllegalArgumentException("Unknown reward step "+action);
                        if(synchronous)journal.markStep(claim.operation(),i,"IN_FLIGHT","DONE");
                    }catch(Exception error){
                        bridge.getLogger().severe("Reward step requires REVIEW: "+claim.operation()+
                            " #"+i+" "+error.getMessage());
                        waiting=true;
                        break;
                    }
                    if(!synchronous){waiting=true;break;}
                }
                if(waiting)continue;
            }
        }catch(IOException error){
            bridge.getLogger().severe("Reward receipt write failed; stop delivery for "+id+": "+error.getMessage());
        }finally{busy.remove(id);}
    }
    private void finishAsync(String operation,UUID id,int index,boolean success){
        if(!Bukkit.isPrimaryThread()){
            Bukkit.getScheduler().runTask(bridge,()->finishAsync(operation,id,index,success));return;
        }
        if(!success){bridge.getLogger().severe("Reward step needs REVIEW after async external action: "+operation+" #"+index);return;}
        try{
            journal.markStep(operation,index,"IN_FLIGHT","DONE");
            // An already-owned tag may call back synchronously while the outer
            // pump is busy. Continue on the next tick, never one minute later.
            Bukkit.getScheduler().runTask(bridge,()->pump(id));
        }catch(IOException | RuntimeException e){
            bridge.getLogger().severe("Reward receipt update failed: "+operation+" "+e.getMessage());
        }
    }
    private boolean preflight(Player player,String step){
        if(step.startsWith("tag:"))return active("NovaraRanksTags")!=null &&
            invokeBoolean(active("NovaraRanksTags"),"rewardTagReady",
                new Class<?>[]{UUID.class,String.class},player.getUniqueId(),step.substring(4));
        if(step.startsWith("pouch:")){
            Plugin pouch=active("NovaraPouches");
            String[] s=step.split(":");
            return pouch!=null && invokeBoolean(pouch,"rewardPouchExists",new Class<?>[]{String.class},s[1]) &&
                invokeBoolean(pouch,"rewardPouchSpace",new Class<?>[]{UUID.class,int.class},
                    player.getUniqueId(),Integer.parseInt(s[2]));
        }
        if(step.startsWith("item:")){
            String[] parts=step.split(":");
            Material material=Material.matchMaterial(parts[1]);
            if(material==null||!material.isItem())return false;
            int count=Integer.parseInt(parts[2]);
            int stack=material.getMaxStackSize();
            int required=(count+stack-1)/stack,free=0;
            for(var item:player.getInventory().getStorageContents())
                if(item==null||item.getType().isAir())free++;
            return free>=required;
        }
        if(step.startsWith("cash:"))return bridge.economyStatus().startsWith("Ready:");
        if(step.startsWith("permission:"))return active("LuckPerms")!=null;
        if(step.startsWith("command:")){
            String template=step.substring(8),name=player.getName();
            if(name==null||!name.matches("[A-Za-z0-9_]{1,16}"))return false;
            String command=template.replace("{player}",name).replace("{uuid}",player.getUniqueId().toString());
            return Bukkit.getCommandMap().getCommand(command.split(" ",2)[0])!=null;
        }
        return false;
    }
    private Plugin active(String name){
        Plugin p=Bukkit.getPluginManager().getPlugin(name);
        return p!=null&&p.isEnabled()?p:null;
    }
    private boolean invokeBoolean(Plugin plugin,String method,Class<?>[] params,Object... args){
        if(plugin==null)return false;
        try{return Boolean.TRUE.equals(plugin.getClass().getMethod(method,params).invoke(plugin,args));}
        catch(ReflectiveOperationException|LinkageError error){return false;}
    }
    @SuppressWarnings("unchecked")
    private void invokeTagGrant(Player p,String tag,String receipt,Consumer<Boolean> done)throws Exception {
        Plugin ranks=active("NovaraRanksTags");
        if(ranks==null)throw new IllegalStateException("NovaraRanksTags unavailable");
        Method method=ranks.getClass().getMethod("grantRewardTag",UUID.class,String.class,String.class,Consumer.class);
        method.invoke(ranks,p.getUniqueId(),tag,receipt,done);
    }
    private void grantPermission(UUID id,String node,Consumer<Boolean> done){
        try {
            var lp=LuckPermsProvider.get();
            lp.getUserManager().loadUser(id).thenCompose(user->{
                user.data().add(Node.builder(node).build());
                return lp.getUserManager().saveUser(user).thenApply(ignored->true);
            }).whenComplete((ok,error)->{
                if(!bridge.isEnabled())return;
                Bukkit.getScheduler().runTask(bridge,()->done.accept(error==null&&Boolean.TRUE.equals(ok)));
            });
        }catch(RuntimeException error){done.accept(false);}
    }
    int pending(){return journal.unresolvedMilestones();}
    private void checkThread(){if(!Bukkit.isPrimaryThread())throw new IllegalStateException("Reward operations require main thread");}
}
