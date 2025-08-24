package com.quarryvision.core.importer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HashSet;
import java.util.Set;

public class IngestProcessor {
    private final Path inbox;
    private final Path manifest;

    public IngestProcessor(Path inboxDir ) throws IOException {
        this.inbox = inboxDir;
        this.manifest = inbox.resolve("manifest.txt");
        Files.createDirectories(inbox);
        if (!Files.exists(manifest)) Files.createFile(manifest);
    }

    public static String sha256(Path file) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        try (var in = Files.newInputStream(file)) {
            byte[] buf = new byte[1 << 20];
            int r;
            while ((r = in.read(buf)) > 0) {
                md.update(buf, 0, r);
            }
        }
        StringBuilder sb = new StringBuilder();
        for (byte b: md.digest()) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private Set<String> loadHashes() throws IOException {
        Set<String> set = new HashSet<>();
        try (var br = Files.newBufferedReader(manifest)) {
            String line;
            while ((line = br.readLine()) != null) {
                int sp = line.indexOf(' ');
                if (sp > 0) {
                    set.add(line.substring(0, sp));
                }
            }
        }
        return set;
    }

    public Path ingest(Path src) throws Exception {
        String hash = sha256(src);
        var known = loadHashes();
        if (known.contains(hash)) {
            // пропускаем дубликат
            return null;
        }
        Path dst = inbox.resolve(src.getFileName().toString());
        Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);

        try (var bw = Files.newBufferedWriter(manifest, StandardCharsets.UTF_8,
                StandardOpenOption.APPEND)) {
            bw.write(hash + " " + src.toAbsolutePath());
            bw.newLine();
        }
        return dst;
    }
}
