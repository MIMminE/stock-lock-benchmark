package nuts.commerce.stocklockbenchmark.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Getter
@Table(name = "stock")
public class Stock {

    @Id
    private Long id;

    @Setter
    @Column(nullable = false)
    private long quantity;

    @Version
    private long version;

    protected Stock() {
    }

    public Stock(Long id, long quantity) {
        this.id = id;
        this.quantity = quantity;
    }

    public void decrementOne() {
        if (quantity <= 0) {
            throw new IllegalStateException("Out of stock");
        }
        this.quantity -= 1;
    }

}