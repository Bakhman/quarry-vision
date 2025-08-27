package com.quarryvision.app;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;


public record Config(Db db, ImportConf imp) {
    public record Db(String url, String user, String pass) {}
    public record ImportConf(List<String> patterns, String inbox) {}

    @SuppressWarnings("unchecked")
    public static Config load() {
        try(InputStream in = Config.class.getResourceAsStream("/application.yaml")) {
            if (in == null) {
                throw new IllegalStateException("application.yaml not found on classpath");
            }
            Yaml yaml = new Yaml();
            Map<String, Object> root = yaml.load(in);

            Map<String, Object> db = (Map<String, Object>) root.get("db");
            Map<String, Object> imp = (Map<String, Object>) root.get("import");

            return  new Config(
                    new Db((String) db.get("url"), (String) db.get("user"), (String) db.get("pass")),
                    new ImportConf((List<String>) imp.get("patterns"), (String) imp.get("inbox"))
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yaml", e);
        }
    }
}
