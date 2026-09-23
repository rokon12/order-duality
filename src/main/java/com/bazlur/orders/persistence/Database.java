package com.bazlur.orders.persistence;

import module java.sql;

/** Hands out connections for one database account. In the application the source is a HikariCP pool. */
public record Database(DataSource dataSource) {
    public Connection open() throws SQLException {
        return dataSource.getConnection();
    }
}
