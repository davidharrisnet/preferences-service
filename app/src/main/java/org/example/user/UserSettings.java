package org.example.user;

import java.util.ArrayList;
import java.util.List;

import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.OneToOne;
import jakarta.persistence.OrderColumn;
import jakarta.persistence.Table;

@Entity
@Table(name = "user_settings")
public class UserSettings {

    public static final int DEFAULT_RESULTS_SIZE = 20;

    public static final int DEFAULT_FONT_SIZE = 12;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @OneToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false, unique = true)
    @OnDelete(action = OnDeleteAction.CASCADE)
    private User user;

    @Column(name = "results_size", nullable = false)
    private int resultsSize = DEFAULT_RESULTS_SIZE;

    @Column(name = "font_size", nullable = false)
    private int fontSize = DEFAULT_FONT_SIZE;

    /** Ordered; list index is persisted as user_settings_fruit.position. */
    @ManyToMany
    @OrderColumn(name = "position", nullable = false)
    @JoinTable(name = "user_settings_fruit",
            joinColumns = @JoinColumn(name = "user_settings_id"),
            inverseJoinColumns = @JoinColumn(name = "fruit_id"))
    private List<Fruit> fruits = new ArrayList<>();

    public UserSettings() {
    }

    /** Created by {@link User}'s constructor; defaults match schema.sql. */
    UserSettings(User user) {
        this.user = user;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public User getUser() {
        return user;
    }

    public void setUser(User user) {
        this.user = user;
    }

    public int getResultsSize() {
        return resultsSize;
    }

    public void setResultsSize(int resultsSize) {
        this.resultsSize = resultsSize;
    }

    public int getFontSize() {
        return fontSize;
    }

    public void setFontSize(int fontSize) {
        this.fontSize = fontSize;
    }

    public List<Fruit> getFruits() {
        return fruits;
    }

    public void setFruits(List<Fruit> fruits) {
        this.fruits = fruits;
    }
}
