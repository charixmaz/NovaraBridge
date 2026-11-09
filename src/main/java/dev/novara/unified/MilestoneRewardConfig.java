package dev.novara.unified;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.File;
import java.util.*;

/**
 * Pure YAML contract shared by Jobs and Ranks via NovaraBridge.
 * The stored delivery plan is immutable after the first claim; future YAML
 * edits affect new claims only.
 */
public final class MilestoneRewardConfig {
    public record Rule(List<String> actions,List<String> descriptions) {
        public Rule { actions=List.copyOf(actions);descriptions=List.copyOf(descriptions); }
    }
    private final Map<String,Rule> rules;
    private MilestoneRewardConfig(Map<String,Rule> rules){this.rules=Map.copyOf(rules);}
    public Rule rule(String id){return rules.get(id);}
    public List<String> description(String id){
        var r=rules.get(id);return r==null?List.of():r.descriptions();
    }
    public Set<String> keys(){return rules.keySet();}

    public static MilestoneRewardConfig load(File file) throws Exception {
        var yaml=new YamlConfiguration();yaml.load(file);
        return parse(yaml);
    }

    public static MilestoneRewardConfig parse(ConfigurationSection yaml) {
        Map<String,Rule> parsed=new LinkedHashMap<>();
        ConfigurationSection jobs=yaml.getConfigurationSection("jobs");
        if(jobs!=null)for(String name:jobs.getKeys(false)) {
            if(!Set.of("MINER","HUNTER","DIGGER","LUMBER","FARMER","BUTCHER","ALCHEMIST").contains(name))
                throw new IllegalArgumentException("Unknown job in rewards.yml: "+name);
            var levels=jobs.getConfigurationSection(name);
            if(levels==null)throw new IllegalArgumentException("Job "+name+" must contain level entries");
            for(String text:levels.getKeys(false)){
                int level=parseInt(text,"job level",1,50);
                parsed.put("job:"+name+":"+level,parseRule(levels.getConfigurationSection(text),"jobs."+name+"."+text));
            }
        }
        ConfigurationSection ranks=yaml.getConfigurationSection("ranks");
        if(ranks!=null)for(String text:ranks.getKeys(false)){
            int rank=parseInt(text,"rank",2,10);
            parsed.put("rank:"+rank,parseRule(ranks.getConfigurationSection(text),"ranks."+text));
        }
        return new MilestoneRewardConfig(parsed);
    }
    /** Validates a self-contained immutable quest bonus snapshot from NovaraJobs. */
    public static Rule parseBonus(String yamlText){
        if(yamlText==null||yamlText.length()>12_000)
            throw new IllegalArgumentException("Invalid quest bonus snapshot");
        var yaml=new YamlConfiguration();
        try{yaml.loadFromString(yamlText);}
        catch(org.bukkit.configuration.InvalidConfigurationException error){
            throw new IllegalArgumentException("Invalid quest reward YAML",error);
        }
        return parseRule(yaml.getConfigurationSection("bonus"),"quest.bonus");
    }
    private static int parseInt(String raw,String what,int min,int max){
        int v;
        try{v=Integer.parseInt(raw);}catch(NumberFormatException invalid){throw new IllegalArgumentException("Invalid "+what+": "+raw);}
        if(v<min||v>max)throw new IllegalArgumentException(what+" must be "+min+"-"+max+": "+raw);
        return v;
    }
    private static Rule parseRule(ConfigurationSection section,String path){
        if(section==null)throw new IllegalArgumentException("Expected reward map at "+path);
        List<String> steps=new ArrayList<>(),labels=new ArrayList<>();
        for(String tag:section.getStringList("tags")){
            if(!tag.matches("[a-z0-9_-]+"))throw new IllegalArgumentException("Invalid tag ID at "+path+": "+tag);
            steps.add("tag:"+tag);labels.add("Tag: "+tag);
        }
        for(Map<?,?> pouch:section.getMapList("pouches")){
            String tier=String.valueOf(pouch.get("rarity")).toLowerCase(Locale.ROOT);
            if(!Set.of("common","uncommon","epic","legendary","apex").contains(tier))
                throw new IllegalArgumentException("Invalid pouch rarity at "+path+": "+tier);
            // Max 36 non-stackable pouches can fit in an empty vanilla inventory.
            int amount=quantity(pouch.get("quantity"),path+".pouches",1,36);
            steps.add("pouch:"+tier+":"+amount);labels.add(amount+"x "+tier+" pouch");
        }
        if(section.contains("money")){
            double cash=section.getDouble("money",-1);
            if(!Double.isFinite(cash)||cash<0||cash>1_000_000||
                    Math.abs(cash*100-Math.rint(cash*100))>0.00001)
                throw new IllegalArgumentException("Invalid money amount at "+path);
            long cents=Math.round(cash*100);
            if(cents>0){steps.add("cash:"+cents);labels.add(String.format(Locale.ROOT,"Cash: $%.2f",cash));}
        }
        for(Map<?,?> item:section.getMapList("items")){
            String material=String.valueOf(item.get("material")).toUpperCase(Locale.ROOT);
            Material type=Material.matchMaterial(material);
            if(type==null||material.equals("AIR")||material.endsWith("_AIR"))throw new IllegalArgumentException("Invalid item at "+path+": "+material);
            int amount=quantity(item.get("amount"),path+".items",1,64);
            steps.add("item:"+material+":"+amount);labels.add(amount+"x "+material);
        }
        for(String node:section.getStringList("permissions")){
            if(!node.matches("[a-zA-Z0-9_.:-]{1,128}"))throw new IllegalArgumentException("Invalid permission at "+path+": "+node);
            steps.add("permission:"+node);labels.add("Permission: "+node);
        }
        for(String command:section.getStringList("commands")){
            if(command.isBlank()||command.startsWith("/")||command.contains("\n")||command.contains("\r")||command.length()>512
                    ||!command.matches("[A-Za-z0-9_:-]+(?: .*)?"))
                throw new IllegalArgumentException("Invalid console command at "+path+": "+command);
            steps.add("command:"+command);labels.add("Additional server reward");
        }
        if(steps.isEmpty())throw new IllegalArgumentException("No rewards at "+path);
        if(steps.size()>32)throw new IllegalArgumentException("Too many reward actions at "+path);
        return new Rule(steps,labels);
    }
    private static int quantity(Object raw,String path,int min,int max){
        int result;
        try{result=Integer.parseInt(String.valueOf(raw));}
        catch(NumberFormatException e){throw new IllegalArgumentException("Invalid quantity at "+path+": "+raw);}
        if(result<min||result>max)throw new IllegalArgumentException("Quantity must be "+min+"-"+max+" at "+path);
        return result;
    }
}
