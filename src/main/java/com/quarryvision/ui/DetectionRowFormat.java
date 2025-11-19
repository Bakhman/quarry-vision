package com.quarryvision.ui;

import com.quarryvision.core.db.DbDetectionRow;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

public class DetectionRowFormat {

    private DetectionRowFormat() {
        // no-op
    }

    static String formatDetectionRow(DbDetectionRow row) {
        OffsetDateTime ts = row.createdAt();
        String when;
        if (ts == null) {
            when = "";
        } else {
            when = ts.atZoneSameInstant(ZoneOffset.systemDefault())
                    .toLocalDateTime()
                    .toString()
                    .replace('T', ' ');
        }
        return "#" + row.id()
                + " | " + row.eventsCount() + " ev"
                + " | merge=" + row.mergeMs()
                + " | " + when
                + " | " + row.videoPath();
    }

    /**
     * Разбор detection-id из строки формата "#<id> | ...".
     * Возвращает null, если строка не подходит под ожидаемый формат.
     */
    static Integer parseDetectionId(String sel) {
        if (sel == null || sel.isBlank()) {
            return null;
        }
        int hash = sel.indexOf('#');
        // ожидаем строго "#<id> ..." с самого начала строки
        if (hash != 0) {
            return null;
        }
        int sp = sel.indexOf(' ');
        if (sp <= hash + 1) {
            return null;
        }
        try {
            return Integer.parseInt(sel.substring(hash + 1, sp));
        } catch (NumberFormatException ignore) {
            return null;
        }
    }
}
