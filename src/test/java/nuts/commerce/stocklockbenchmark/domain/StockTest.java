package nuts.commerce.stocklockbenchmark.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StockTest {

    @Test
    void decrementOneReducesQuantity() {
        Stock stock = new Stock(1L, 3L);

        stock.decrementOne();

        assertThat(stock.getQuantity()).isEqualTo(2L);
    }

    @Test
    void decrementOneRejectsOutOfStock() {
        Stock stock = new Stock(1L, 0L);

        assertThatThrownBy(stock::decrementOne)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Out of stock");
    }
}
