package com.quarryvision.core.db;

import com.quarryvision.app.Config;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
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
        ds=new HikariDataSource(hc);
        log.info("Pg: pool started url={}", cfg.db().url());

        // Flyway migrations (classpath:db/migration)
        Flyway fw = Flyway.configure()
                .dataSource(ds)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .load();
        fw.migrate();
        log.info("Pg: flyway migrate don");
    }

    public static Connection get() throws SQLException {
        if (ds == null) init();
        return ds.getConnection();
    }

    /** upsert по пути видео, возвращает id видео. */
    public static int upsertVideo(Path path, double fps, long frames) {
        final String sel = "select id from videos where path = ?";
        final String ins = "insert into videos(path,fps,frames) values(?,?,?) returning id";
        final String upd = "update videos set fps=?, frames=? where id=? returning id";
        try (Connection c = get()) {
            // select
            Integer id = null;
            try (PreparedStatement ps = c.prepareStatement(sel)) {
                ps.setString(1, path.toString());
                try (ResultSet rs = ps.executeQuery()) {
                    if (rs.next()) id = rs.getInt(1);
                }
            }
            if (id == null) {
                try (PreparedStatement ps = c.prepareStatement(ins)) {
                    ps.setString(1, path.toString());
                    ps.setDouble(2, fps);
                    ps.setLong(3, frames);
                    try(ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
            } else {
                try (PreparedStatement ps = c.prepareStatement(upd)) {
                    ps.setDouble(1, fps);
                    ps.setLong(2, frames);
                    ps.setInt(3, id);
                    try (ResultSet rs = ps.executeQuery()) {
                        rs.next();
                        id = rs.getInt(1);
                    }
                }
            }
            return id;
        } catch(SQLException e){
            throw new RuntimeException("upsertVideo failed for " + path, e);
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
    public static void close() {
        System.out.println("[Pg] close(): заглушка");
    }
}
