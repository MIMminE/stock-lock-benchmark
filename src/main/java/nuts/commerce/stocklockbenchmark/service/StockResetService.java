package nuts.commerce.stocklockbenchmark.service;

import nuts.commerce.stocklockbenchmark.domain.Stock;
import nuts.commerce.stocklockbenchmark.repository.StockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class StockResetService {

    private final StockRepository stockRepository;

    public StockResetService(StockRepository stockRepository) {
        this.stockRepository = stockRepository;
    }

    @Transactional
    public void reset(long quantity) {
        Stock stock = stockRepository.findById(1L).orElseGet(() -> new Stock(1L, quantity));
        stock.setQuantity(quantity);
        stockRepository.save(stock);
    }

    @Transactional(readOnly = true)
    public long getQuantity() {
        return stockRepository.findById(1L).map(Stock::getQuantity).orElse(0L);
    }
}