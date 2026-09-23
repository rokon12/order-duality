package com.bazlur.orders.persistence.springdatajdbc;

import org.springframework.data.repository.ListCrudRepository;

public interface OrderAggregateRepository extends ListCrudRepository<OrderAggregate, Long> {}
