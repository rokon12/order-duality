package com.bazlur.orders.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** One MySQL server, two accounts: the REST API writer and the read-only agent, each with its own pool. */
@ConfigurationProperties("orders.database")
public record DatabaseProperties(String url, String user, String password, int poolSize,
                                 String agentUser, String agentPassword, int agentPoolSize) {
    // A record's generated toString would print the passwords into logs and exception messages.
    @Override public String toString() {
        return "DatabaseProperties[url=" + url + ", user=" + user + ", poolSize=" + poolSize
                + ", agentUser=" + agentUser + ", agentPoolSize=" + agentPoolSize + ", passwords=***]";
    }
}
