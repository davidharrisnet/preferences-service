package org.example.user;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Reference data seeded by data.sql; ids are fixed, not generated. Fruits are retired by setting active to false,
 * never deleted.
 */
@Entity
@Table(name = "fruit")
public class Fruit {

    @Id
    private Long id;

    @Column(nullable = false, unique = true, length = 50)
    private String name;

    @Column(nullable = false)
    private boolean active;

    public Fruit() {
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public boolean isActive() {
        return active;
    }
}
