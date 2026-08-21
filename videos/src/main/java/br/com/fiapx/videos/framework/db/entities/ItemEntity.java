package br.com.fiapx.videos.framework.db.entities;

import io.quarkus.hibernate.reactive.panache.PanacheEntity;
import io.smallrye.mutiny.Uni;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

@Entity
@Table(name = "item")
public class ItemEntity extends PanacheEntity {
    @Column(name = "nome", nullable = false)
    public String nome;

    public long id() {
        return this.id;
    }

    public Uni<ItemEntity> persistir() {
        return persist();
    }

    public static Uni<ItemEntity> buscaPorId(long id) {
        return findById(id);
    }
}
