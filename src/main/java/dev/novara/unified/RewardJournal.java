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
    private void save() throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = file.resolveSibling(file.getFileName()+".tmp");
        try (var out = Files.newOutputStream(temporary)) { records.store(out,"NovaraBridge reward receipts: never erase to retry a payment"); }
        try (var channel = FileChannel.open(temporary,StandardOpenOption.WRITE)) { channel.force(true); }
        Files.move(temporary,file,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
    }
}
