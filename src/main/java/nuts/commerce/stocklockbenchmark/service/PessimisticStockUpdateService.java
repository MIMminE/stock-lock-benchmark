package nuts.commerce.stocklockbenchmark.service;

import nuts.commerce.stocklockbenchmark.domain.Stock;
import nuts.commerce.stocklockbenchmark.repository.PessimisticStockRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class PessimisticStockUpdateService implements StockUpdateService {

    private final PessimisticStockRepository repo;

    public PessimisticStockUpdateService(PessimisticStockRepository repo) {
        this.repo = repo;
    }

    @Override
    public String lockType() {
        return "pessimistic";
    }

    @Override
    @Transactional
    public boolean tryReserveOne(Long stockId) {
        Stock stock = repo.findByIdForUpdate(stockId).orElseThrow();
        if (stock.getQuantity() <= 0) return false;

        stock.decrementOne();
        repo.save(stock);
        return true;
    }
}