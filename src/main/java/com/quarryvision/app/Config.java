package com.quarryvision.app;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;


public record Config(Db db, ImportConf imp) {
    public record Db(String url, String user, String pass) {}
    public record ImportConf(List<String> patterns, String inbox) {}

    public static Config load() {
        try (InputStream in = Config.class.getResourceAsStream("/application.yaml")) {
            Map<?, ?> m = new Yaml().load(in);
            Map<?, ?> db = (Map<?, ?>)((Map<?, ?>)m.get("db"));
            Map<?, ?> imp = (Map<?, ?>)((Map<?, ?>)m.get("import"));
            return new Config(
                    new Db((String) db.get("url"), (String) db.get("user"), (String) db.get("pass")),
                    new ImportConf((List<String>) imp.get("patterns"), (String) imp.get("inbox"))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yaml", e);
        }
    }
}
