package com.bazlur.orders.persistence.jpa;

import module java.base;

import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

public interface OrderEntityRepository extends Repository<OrderEntity, Long> {
    // Fetch the customer, lines and products in the same query; without the join fetches this is N+1.
    @Query("""
            select o from OrderEntity o
              join fetch o.customer
              left join fetch o.items i
              left join fetch i.product
            where o.id = :id
            """)
    Optional<OrderEntity> findWithDetails(long id);
}
