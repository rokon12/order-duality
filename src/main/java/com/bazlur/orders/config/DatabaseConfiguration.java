package com.bazlur.orders.config;

import module java.base;

import com.bazlur.orders.application.OrderService;
import com.bazlur.orders.persistence.ConventionalOrderRepository;
import com.bazlur.orders.persistence.Database;
import com.bazlur.orders.persistence.OrderDocumentRepository;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/** Connection pools, repositories and the write service for the REST API. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DatabaseProperties.class)
public class DatabaseConfiguration {
    // Each account gets its own pool; Spring closes them on shutdown.
    @Bean(destroyMethod = "close")
    HikariDataSource apiPool(DatabaseProperties properties) {
        return pool("orders-api", properties.url(), properties.user(), properties.password(), properties.poolSize(), false);
    }

    // The agent only reads, so its connections are read-only as well as its grants.
    @Bean(destroyMethod = "close")
    HikariDataSource agentPool(DatabaseProperties properties) {
        return pool("orders-agent", properties.url(), properties.agentUser(), properties.agentPassword(), properties.agentPoolSize(), true);
    }

    @Bean @Primary
    Database apiDatabase(@Qualifier("apiPool") HikariDataSource pool) { return new Database(pool); }

    @Bean
    Database agentDatabase(@Qualifier("agentPool") HikariDataSource pool) { return new Database(pool); }

    @Bean
    OrderDocumentRepository orderDocumentRepository(Database database) { return new OrderDocumentRepository(database); }

    @Bean
    ConventionalOrderRepository conventionalOrderRepository(Database database) { return new ConventionalOrderRepository(database); }

    @Bean
    OrderService orderService(Database database, OrderDocumentRepository repository) { return new OrderService(database, repository); }

    @Bean
    ApplicationRunner reportDatabaseVersion(Database database) {
        return _ -> {
            try (var connection = database.open(); var statement = connection.createStatement();
                 var rows = statement.executeQuery("SELECT VERSION(), @@version_comment")) {
                rows.next();
                IO.println("MySQL %s — %s".formatted(rows.getString(1), rows.getString(2)));
            }
        };
    }

    private static HikariDataSource pool(String name, String url, String user, String password, int size, boolean readOnly) {
        var config = new HikariConfig();
        config.setPoolName(name);
        config.setJdbcUrl(url);
        config.setUsername(user);
        config.setPassword(password);
        config.setMaximumPoolSize(size);
        config.setReadOnly(readOnly);
        config.setConnectionTimeout(Duration.ofSeconds(10).toMillis());
        return new HikariDataSource(config);
    }
}
