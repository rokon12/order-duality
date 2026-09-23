package com.bazlur.orders.persistence.springdatajdbc;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("customers")
public record CustomerAggregate(@Id Long id, String name, String email) {}
