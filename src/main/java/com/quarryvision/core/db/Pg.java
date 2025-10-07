package com.quarryvision.core.db;

import com.quarryvision.app.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.*;
import java.time.*;
import java.util.ArrayList;
import java.util.List;


// заглушка БД
public final class Pg {
    private static final Logger log = LoggerFactory.getLogger(Pg.class);
    private static HikariDataSource ds;

    private Pg(){}

    public static synchronized void init() {
        if (ds != null) return;
        Config cfg = Config.load();
        HikariConfig hc = new HikariConfig();
        hc.setJdbcUrl(cfg.db().url());
        hc.setUsername(cfg.db().user());
        hc.setPassword(cfg.db().pass());
        hc.setMaximumPoolSize(5);
        hc.setMinimumIdle(1);
        hc.setPoolName("qv-pool");
        // быстрые таймауты и health-check
        hc.setConnectionTimeout(5000);
        hc.setValidationTimeout(3000);
        hc.setIdleTimeout(300000);
        hc.setMaxLifetime(1800000);
        hc.setConnectionTestQuery("SELECT 1");
        ds=new HikariDataSource(hc);
        log.info("Pg: pool started url={}", cfg.db().url());

        // Flyway migrations (classpath:db/migration)
        Flyway fw = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        fw.migrate();
        log.info("Pg: flyway migrate done");
    }

    public static Connection get() throws SQLException {
        if (ds == null) init();
        return ds.getConnection();
    }

    /** Вставить/обновить видео по уникальному path. Возвращает id. */
    public static int upsertVideo(Path path, double fps, long frames) {
        final String sql = """
        INSERT INTO videos(path, fps, frames)
        VALUES (?, ?, ?)
        ON CONFLICT(path) DO UPDATE
          SET fps = EXCLUDED.fps,
              frames = EXCLUDED.frames
        RETURNING id
        """;
        try (var c = get();
             var ps = c.prepareStatement(sql)) {
            ps.setString(1, path.toString());
            ps.setDouble(2, fps);
            ps.setLong(3, frames);
            try (var rs = ps.executeQuery()) {
                if (rs.next()) return rs.getInt(1);
            }
            throw new RuntimeException("upsertVideo failed for " + path);
        } catch (SQLException e) {
            throw new RuntimeException("upsertVideo failed for " + path + " sqlstate=" + e.getSQLState(), e);
        }
    }

    /** Вставить detection с событиями, вернуть id. */
    public static int insertDetection(int videoId, int mergeMs, List<Instant> stamps) {
        final String insDet = "insert into detections(video_id, merge_ms, events_count) values(?,?,?) returning id";
        final String insEvt = "insert into events(detection_id, t_ms) values (?,?)";
        try (Connection c = get()) {
            c.setAutoCommit(false);
            int detId;
            try (PreparedStatement ps = c.prepareStatement(insDet)) {
                ps.setInt(1, videoId);
                ps.setInt(2, mergeMs);
                ps.setInt(3, stamps == null ? 0 : stamps.size());
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    detId = rs.getInt(1);
                }
            }
            if (stamps != null && !stamps.isEmpty()) {
                try (PreparedStatement ps = c.prepareStatement(insEvt)) {
                    for (Instant t: stamps) {
                        ps.setInt(1, detId);
                        ps.setLong(2, t.toEpochMilli());
                        ps.addBatch();
                    }
                    ps.executeBatch();
                }
            }
            c.commit();
            return detId;
        } catch (SQLException e) {
            throw new RuntimeException("insertDetection failed for video_id=" + videoId, e);
        }
    }

    /** Последние детекции: "#<id> | <events> ev | merge=<ms> | <YYYY-MM-DD HH:MM> | <path>" */
    public static List<String> listRecentDetections(int limit) {
        return listRecentDetections(limit, 0);
    }

    /** Последние детекции с пагинацией. */
    public static List<String> listRecentDetections(int limit, int offset) {
        final String sql = """
                SELECT d.id,
                       v.path,
                       d.events_count,
                       d.merge_ms,
                       d.created_at
                FROM detections d
                JOIN videos v ON v.id = d.video_id
                ORDER BY d.id DESC
                LIMIT ? OFFSET ?
                """;
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql)
        ) {
            ps.setInt(1, Math.max(1, limit));
            ps.setInt(2, Math.max(0, offset));
            try (ResultSet rs = ps.executeQuery()) {
                List<String> out = new ArrayList<>();
                while (rs.next()) {
                    int id = rs.getInt(1);
                    String path = rs.getString(2);
                    int events = rs.getInt(3);
                    int mergeMs = rs.getInt(4);
                    OffsetDateTime ts = rs.getObject(5, OffsetDateTime.class);
                    String when = ts == null
                            ? ""
                            : ts.atZoneSameInstant(ZoneOffset.systemDefault()).toLocalDateTime().toString().replace('T', ' ');
                    out.add("#" + id + " | " + events + " ev | merge=" + mergeMs + " | " + when + " | " + path);
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listRecentDetections failed", e);
        }
    }
    /** Временные метки событий для детекции. */
    public static List<Long> listEventsMs(int detectionId) {
        final String sql = """
                SELECT t_ms
                FROM events
                WHERE detection_Id = ?
                ORDER BY t_ms
                """;
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql)
        ) {
            ps.setInt(1, detectionId);
            try (ResultSet rs = ps.executeQuery()) {
                List<Long> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(rs.getLong(1));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listEventsMs failed for detection_id=" + detectionId, e);
        }
    }

    /** Удалить детекцию; события удаляются каскадно. */
    public static void deleteDetection(int detectionId) {
        final String sql = "DELETE FROM detections WHERE id = ?";
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql)) {
            ps.setInt(1, detectionId);
            ps.executeUpdate();
        } catch (SQLException e) {
            throw new RuntimeException("deleteDetection failed for id=" + detectionId, e);
        }
    }

    /** Простая статистика по таблицам. */
    public static long countVideos() {
        return count("SELECT count(*) FROM videos");
    }

    public static long countDetections() {
        return count("SELECT count(*) FROM detections");
    }

    public static long countEvents() {
        return count("SELECT count(*) FROM events");
    }

    private static long count(String sql) {
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()
        ) {
            rs.next();
            return rs.getLong(1);
        } catch (SQLException e) {
            throw new RuntimeException("count failed for: " + sql, e);
        }
    }

    /** Агрегаты по дням недели (ISO: 1=MON..7=SUN). */
    public static List<DbWeekAgg> listWeekAgg() {
        final String sql = """
                SELECT CAST(EXTRACT(ISODOW FROM created_at) AS int) AS dow,
                    count(*) AS det_cnt,
                    coalesce(sum(events_count),0) AS ev_cnt
                FROM detections
                GROUP BY dow
                """;
        long[] det = new long[8]; // 1..7
        long[] ev  = new long[8];
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()
        ) {
            while (rs.next()) {
                int dow  = rs.getInt(1);
                det[dow] = rs.getLong(2);
                ev[dow]  = rs.getLong(3);
            }
            List<DbWeekAgg> out = new ArrayList<>(7);
            for (int i = 1; i <= 7; i++) {
                out.add(new DbWeekAgg(DayOfWeek.of(i), det[i], ev[i]));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("listWeekAgg failed", e);
        }
    }

    /** Агрегаты по видео, последние по активности. */
    public static List<DbVideoAgg> listVideoAgg(int limit) {
        final String sql = """
                SELECT v.path,
                    count(*) AS det_cnt,
                    coalesce(sum(d.events_count),0) AS ev_cnt
                FROM detections d
                JOIN videos v ON v.id = d.video_id
                GROUP BY v.path
                ORDER BY max(d.created_at) DESC
                LIMIT ?
                """;
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql)
        ) {
            ps.setInt(1, Math.max(1, limit));
            try (ResultSet rs = ps.executeQuery()) {
                List<DbVideoAgg> out = new ArrayList<>();
                while (rs.next()) {
                    out.add(new DbVideoAgg(
                            rs.getString(1),
                            rs.getLong(2),
                            rs.getLong(3)
                    ));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listVideoAgg failed", e);
        }
    }

    /** Агрегаты по merge_ms. */
    public static List<DbMergeAgg> listMergeAgg() {
        final String sql = """
                SELECT merge_ms,
                    count(*) AS det_cnt,
                    coalesce(sum(events_count),0) AS ev_count
                FROM detections
                GROUP BY merge_ms
                ORDER BY merge_ms
                """;
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()
        ){
            List<DbMergeAgg> out =  new ArrayList<>();
            while (rs.next()) {
                out.add(new DbMergeAgg(
                        rs.getInt(1),
                        rs.getLong(2),
                        rs.getLong(3)
                ));
            }
            return out;
        } catch (SQLException e) {
            throw new RuntimeException("listMergeAgg failed", e);
        }
    }

    /** Агрегаты по дням за последние lastDays. */
    public static List<DbDailyAgg> listDailyAgg(int lastDays) {
        int days = Math.max(1, lastDays);
        final String sql = """
                SELECT date(created_at) AS d,
                    count(*) AS det_cnt,
                    coalesce(sum(events_count), 0) AS ev_cnt
                FROM detections
                WHERE created_at >= ?
                GROUP BY d
                ORDER BY d DESC
                """;
        Instant bound = Instant.now().minusSeconds(days * 86400L);
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql)
        ){
            ps.setTimestamp(1, Timestamp.from(bound));
            try (ResultSet rs = ps.executeQuery()) {
                List<DbDailyAgg> out = new ArrayList<>();
                while (rs.next()) {
                    LocalDate day = rs.getObject(1, LocalDate.class);
                    long det = rs.getLong(2);
                    long ev = rs.getLong(3);
                    out.add(new DbDailyAgg(day, det, ev));
                }
                return out;
            }
        } catch (SQLException e) {
            throw new RuntimeException("listDailyAgg failed", e);
        }
    }

    /** Путь к видео по detection_id. */
    public static String findVideoPathByDetection(int detectionId) {
        final String sql = """
                SELECT v.path
                FROM detections d
                JOIN videos v ON v.id = d.video_id
                WHERE d.id = ?
                """;
        try (Connection c = get();
             PreparedStatement ps = c.prepareStatement(sql);
        ) {
            ps.setInt(1 , detectionId);
            try (ResultSet rs = ps.executeQuery()) {
                if (rs.next()) return rs.getString(1);
                return null;
            }
        } catch (SQLException e) {
            throw new RuntimeException("findVideoPathByDetection failed for id=" + detectionId,  e);
        }
    }

    public static void close() {
        System.out.println("[Pg] close(): заглушка");
    }
}
