package com.bazlur.orders.persistence.springdatajdbc;

import module java.base;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Table;

@Table("products")
public record ProductAggregate(@Id Long id, String sku, String name, BigDecimal price) {}
