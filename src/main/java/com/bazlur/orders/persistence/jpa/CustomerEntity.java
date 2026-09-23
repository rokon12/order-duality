package com.bazlur.orders.persistence.jpa;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

@Entity
@Immutable
@Table(name = "customers")
public class CustomerEntity {
    @Id private Long id;
    private String name;
    private String email;

    protected CustomerEntity() {}

    public Long getId() { return id; }
    public String getName() { return name; }
    public String getEmail() { return email; }
}
