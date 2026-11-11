package org.example.user;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;

public interface FruitRepository extends JpaRepository<Fruit, Long> {

    /** Active fruits in data.sql order (ids are assigned in that order). */
    List<Fruit> findAllByActiveTrueOrderByIdAsc();

}
