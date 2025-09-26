package com.quarryvision.app;

import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.List;
import java.util.Map;


public record Config(Db db, ImportConf imp, DetectConf detection) {
    public record Db(String url, String user, String pass) {}
    public record ImportConf(List<String> patterns, String inbox, String source) {}
    public record DetectConf(int stepFrames, int diffThreshold, double eventRatio,
                             int cooldownFrames, int minChangedPixels, int morphW, int morphH,
                             int mergeMs) {}

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
            Map<String, Object> det = (Map<String, Object>) root.getOrDefault("detect", Map.of());
            Map<String, Object> mk  = (Map<String, Object>) (det.getOrDefault("morphKernel", Map.of()));

            int stepFrames       = det.get("stepFrames")       != null ? ((Number) det.get("stepFrames")).intValue()       : 15;
            int diffThreshold    = det.get("diffThreshold")    != null ? ((Number) det.get("diffThreshold")).intValue()    : 45;
            double eventRatio    = det.get("eventRatio")       != null ? ((Number) det.get("eventRatio")).doubleValue()     : 0.14;
            int cooldownFrames   = det.get("cooldownFrames")   != null ? ((Number) det.get("cooldownFrames")).intValue()   : 120;
            int minChangedPixels = det.get("minChangedPixels") != null ? ((Number) det.get("minChangedPixels")).intValue() : 25000;
            int morphW           = mk.get("w")                 != null ? ((Number) mk.get("w")).intValue()                 : 3;
            int morphH           = mk.get("h")                 != null ? ((Number) mk.get("h")).intValue()                 : 3;
            int mergeMs = det.get("mergeMs") != null ? ((Number) det.get("mergeMs")).intValue() : 4000;
            return  new Config(
                    new Db((String) db.get("url"), (String) db.get("user"), (String) db.get("pass")),
                    new ImportConf((List<String>) imp.get("patterns"), (String) imp.get("inbox"), (String) imp.get("source")),
                    new DetectConf(stepFrames, diffThreshold, eventRatio, cooldownFrames, minChangedPixels, morphW, morphH, mergeMs)
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to load application.yaml", e);
        }
    }
}
