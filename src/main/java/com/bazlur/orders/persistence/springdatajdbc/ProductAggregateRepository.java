package com.bazlur.orders.persistence.springdatajdbc;

import org.springframework.data.repository.ListCrudRepository;

public interface ProductAggregateRepository extends ListCrudRepository<ProductAggregate, Long> {}
