package dev.novara.unified;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;

/** Small, infrequent season payouts: persist intent before each external side effect. */
final class RewardJournal {
    private final Path file;
    private final Properties records = new Properties();
    RewardJournal(Path file) throws IOException {
        this.file = file;
        if (Files.exists(file)) try (var in = Files.newInputStream(file)) { records.load(in); }
    }
    static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    synchronized boolean deliver(String operation, UUID player, List<String> plan, java.util.function.Predicate<String> action) throws IOException {
        String key = digest(operation);
        String identity = player+":"+digest(String.join("\n",plan));
        String previous = records.getProperty(key+".plan");
        if (previous != null && !previous.equals(identity)) throw new IllegalStateException("Reward recipient or configuration changed for an existing delivery");
        records.setProperty(key+".plan",identity);
        for (int i=0;i<plan.size();i++) {
            String step = key+"."+i;
            String state = records.getProperty(step,"");
            if (state.equals("DONE")) continue;
            if (state.equals("PENDING")) throw new IllegalStateException("Reward step has uncertain outcome; check reward-journal.properties before manual reconciliation");
            records.setProperty(step,"PENDING"); save();
            if (!action.test(plan.get(i))) return false;
            records.setProperty(step,"DONE"); save();
        }
        return true;
    }
    /** Journaled, immutable milestone delivery. Astral's existing journal stays unchanged. */
    record Claim(String operation,UUID player,List<String> steps,List<String> states) {}
    synchronized void queueMilestone(String operation,UUID player,List<String> steps)throws IOException {
        if(operation==null||!operation.matches("(job:[0-9a-f-]{36}:[A-Z_]+:[0-9]+|rank:[0-9a-f-]{36}:[0-9]+|quest:[0-9a-f-]{36}:[a-z0-9_-]{3,40}:[0-9]{4}-[0-9]{2}-[0-9]{2})"))
            throw new IllegalArgumentException("Invalid milestone operation");
        if(player==null||steps==null||steps.isEmpty()||steps.size()>32)
            throw new IllegalArgumentException("Empty or invalid milestone reward");
        String root="milestone."+digest(operation);
        String signature=player+":"+digest(String.join("\n",steps));
        String prior=records.getProperty(root+".signature");
        if(prior!=null){
            // YAML edits cannot silently replace a previously claimed plan.
            // The original plan remains stored for retry and reconciliation.
            return;
        }
        records.setProperty(root+".signature",signature);
        records.setProperty(root+".operation",operation);
        records.setProperty(root+".player",player.toString());
        records.setProperty(root+".count",Integer.toString(steps.size()));
        for(int i=0;i<steps.size();i++){
            records.setProperty(root+".action."+i,Base64.getEncoder()
                .encodeToString(steps.get(i).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
            records.setProperty(root+".state."+i,"READY");
        }
        save();
    }
    synchronized List<Claim> pendingMilestones(UUID player) {
        List<Claim> result=new ArrayList<>();
        for(String key:records.stringPropertyNames()) {
            if(!key.startsWith("milestone.")||!key.endsWith(".operation"))continue;
            String root=key.substring(0,key.length()-".operation".length());
            if(!player.toString().equals(records.getProperty(root+".player")))continue;
            int count=Integer.parseInt(records.getProperty(root+".count","0"));
            if(count<1||count>32)continue;
            List<String> steps=new ArrayList<>(),states=new ArrayList<>();
            boolean pending=false;
            for(int i=0;i<count;i++){
                String encoded=records.getProperty(root+".action."+i,"");
                steps.add(new String(Base64.getDecoder().decode(encoded),java.nio.charset.StandardCharsets.UTF_8));
                String status=records.getProperty(root+".state."+i,"REVIEW");
                states.add(status);if(!status.equals("DONE"))pending=true;
            }
            if(pending)result.add(new Claim(records.getProperty(root+".operation"),player,
                List.copyOf(steps),List.copyOf(states)));
        }
        result.sort(Comparator.comparing(Claim::operation));
        return result;
    }
    synchronized boolean alreadyQueued(String operation) {
        return records.containsKey("milestone."+digest(operation)+".signature");
    }
    synchronized void markStep(String operation,int step,String expected,String next)throws IOException {
        String root="milestone."+digest(operation);
        String key=root+".state."+step;
        if(!records.containsKey(root+".signature")||!expected.equals(records.getProperty(key)))
            throw new IllegalStateException("Reward step not in "+expected+": "+operation+" #"+step);
        records.setProperty(key,next);save();
    }
    synchronized int unresolvedMilestones() {
        int result=0;
        for(String key:records.stringPropertyNames())if(key.endsWith(".operation")&&key.startsWith("milestone.")){
            String root=key.substring(0,key.length()-".operation".length());
            int count=Integer.parseInt(records.getProperty(root+".count","0"));
            for(int i=0;i<count;i++){
                String state=records.getProperty(root+".state."+i);
                if(!"DONE".equals(state)){result++;break;}
            }
        }
        return result;
    }
    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName()+".tmp");
        try (var out = Files.newOutputStream(temporary)) { records.store(out,"NovaraBridge reward receipts: never erase to retry a payment"); }
        try (var channel = FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
