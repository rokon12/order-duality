package com.bazlur.orders.config;

import com.bazlur.orders.persistence.jpa.JpaOrderReader;
import com.bazlur.orders.persistence.jpa.OrderEntityRepository;
import com.bazlur.orders.persistence.springdatajdbc.CustomerAggregateRepository;
import com.bazlur.orders.persistence.springdatajdbc.OrderAggregateRepository;
import com.bazlur.orders.persistence.springdatajdbc.ProductAggregateRepository;
import com.bazlur.orders.persistence.springdatajdbc.SpringDataJdbcOrderReader;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jdbc.repository.config.EnableJdbcRepositories;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

/**
 * The comparison read paths. Both Spring Data modules are on the classpath, so each one is pointed at its
 * own package to keep repository detection unambiguous. Both use the API account's pool.
 */
@Configuration(proxyBeanMethods = false)
@EnableJpaRepositories(basePackageClasses = OrderEntityRepository.class)
@EnableJdbcRepositories(basePackageClasses = OrderAggregateRepository.class)
public class ComparisonConfiguration {
    @Bean
    JpaOrderReader jpaOrderReader(OrderEntityRepository repository) {
        return new JpaOrderReader(repository);
    }

    @Bean
    SpringDataJdbcOrderReader springDataJdbcOrderReader(OrderAggregateRepository orders, CustomerAggregateRepository customers,
                                                        ProductAggregateRepository products) {
        return new SpringDataJdbcOrderReader(orders, customers, products);
    }
}
