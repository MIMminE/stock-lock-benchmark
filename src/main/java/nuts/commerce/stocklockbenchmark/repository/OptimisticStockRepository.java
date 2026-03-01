package nuts.commerce.stocklockbenchmark.repository;

import nuts.commerce.stocklockbenchmark.domain.Stock;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public class OptimisticStockRepository {

    private final StockRepository stockRepository;

    public OptimisticStockRepository(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    public Optional<Stock> find(Long id) {
        return stockRepository.findById(id);
    }

    public Stock save(Stock stock) {
        return stockRepository.save(stock);
    }
}