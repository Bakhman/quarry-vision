package com.quarryvision.tools;

import org.opencv.core.Mat;
import org.opencv.core.MatOfInt;
import org.opencv.imgcodecs.Imgcodecs;
import org.opencv.videoio.VideoCapture;
import org.opencv.videoio.Videoio;

import java.io.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

public final class SnapshotCli {
    record Event(String video, long tMs, String detId, String evtId) {}

    public static void main(String[] args) throws Exception {
        nu.pattern.OpenCV.loadLocally();

        Map<String,String> a = parseArgs(args);
        String mode   = a.getOrDefault("mode","csv");                // csv|db
        Path outDir   = Paths.get(a.getOrDefault("out","snapshots"));
        int quality   = Integer.parseInt(a.getOrDefault("quality","90"));

        List<Event> events = switch (mode) {
            case "csv" -> loadCsv(Paths.get(req(a,"csv")));
            case "db"  -> loadDb(
                    req(a,"pgurl"),
                    a.getOrDefault("pguser","quarry"),
                    a.getOrDefault("pgpass","quarry"),
                    // SQL должен вернуть: video_path, event_ms, detection_id, event_id
                    a.getOrDefault("sql",
                            "select v.path, e.event_ms, e.detection_id, e.id " +
                                    "from events e join videos v on v.id=e.video_id order by e.id")
            );
            default -> throw new IllegalArgumentException("mode must be csv|db");
        };

        Files.createDirectories(outDir);
        int ok=0, err=0;
        for (Event e : events) {
            try {
                Path dir = outDir.resolve(baseNoExt(e.video)).resolve("det_" + e.detId);
                Files.createDirectories(dir);
                Path out = dir.resolve("event_" + e.evtId + ".jpg");
                grab(e.video, e.tMs, out, quality);
                System.out.println("OK  " + out);
                ok++;
            } catch (Exception ex) {
                System.err.println("ERR " + e + " :: " + ex.getMessage());
                err++;
            }
        }
        System.out.printf("Done. saved=%d failed=%d%n", ok, err);
    }

    static void grab(String videoPath, long ms, Path out, int jpegQuality) {
        VideoCapture cap = new VideoCapture();
        try {
            if (!cap.open(videoPath)) {
                throw new IllegalStateException("open failed: " + videoPath);
            }
            cap.set(Videoio.CAP_PROP_POS_MSEC, ms);
            Mat frame = new Mat();
            try {
                if (!cap.read(frame) || frame.empty()) {
                    throw new IllegalStateException("read failed at " + ms + " ms");
                }
                Imgcodecs.imwrite(out.toString(), frame,
                        new MatOfInt(Imgcodecs.IMWRITE_JPEG_QUALITY, jpegQuality));
            } finally {
                frame.release();
            }
        } finally {
            cap.release();
        }
    }

    static List<Event> loadCsv(Path csv) throws IOException {
        List<Event> list = new ArrayList<>();
        try (BufferedReader br = Files.newBufferedReader(csv)) {
            String line; boolean header=true;
            while ((line = br.readLine()) != null) {
                if (header) { header=false; if (line.toLowerCase().contains("video_path")) continue; }
                String[] p = line.split(",", -1);
                if (p.length < 4) continue;
                list.add(new Event(p[0], Long.parseLong(p[1]), p[2], p[3]));
            }
        }
        return list;
    }

    static List<Event> loadDb(String url, String user, String pass, String sql) throws SQLException {
        List<Event> list = new ArrayList<>();
        try (Connection c = DriverManager.getConnection(url, user, pass);
             Statement st = c.createStatement();
             ResultSet rs = st.executeQuery(sql)) {
            while (rs.next()) {
                list.add(new Event(
                        rs.getString(1), rs.getLong(2),
                        String.valueOf(rs.getLong(3)), String.valueOf(rs.getLong(4))
                ));
            }
        }
        return list;
    }

    static Map<String,String> parseArgs(String[] args) {
        Map<String,String> m = new HashMap<>();
        for (String s : args) {
            int i = s.indexOf('=');
            if (i>0) m.put(s.substring(0,i).replaceFirst("^--",""), s.substring(i+1));
        }
        return m;
    }
    static String req(Map<String,String> a, String k) {
        String v = a.get(k);
        if (v==null || v.isBlank()) throw new IllegalArgumentException("missing --"+k);
        return v;
    }
    static String baseNoExt(String path) {
        String name = Paths.get(path).getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }
}
