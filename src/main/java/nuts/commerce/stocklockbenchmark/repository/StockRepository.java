package nuts.commerce.stocklockbenchmark.repository;

import nuts.commerce.stocklockbenchmark.domain.Stock;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockRepository extends JpaRepository<Stock, Long> {
}