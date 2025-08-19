package com.quarryvision.core.db;

// заглушка БД
public final class Pg {
    private Pg() {}

    public static void init() {
        System.out.println("[Pg] init(): заглушка (БД подключим позже)");
    }

    public static void close() {
        System.out.println("[Pg] close(): заглушка");
    }
}
