package com.bazlur.orders.persistence.springdatajdbc;

import org.springframework.data.repository.ListCrudRepository;

public interface CustomerAggregateRepository extends ListCrudRepository<CustomerAggregate, Long> {}
