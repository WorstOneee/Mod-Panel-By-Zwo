package dev.zwo.modpanel.storage;

import java.sql.SQLException;

@FunctionalInterface
public interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
}
