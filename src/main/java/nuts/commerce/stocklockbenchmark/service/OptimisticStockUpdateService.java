package nuts.commerce.stocklockbenchmark.service;

import nuts.commerce.stocklockbenchmark.domain.Stock;
import nuts.commerce.stocklockbenchmark.repository.OptimisticStockRepository;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OptimisticStockUpdateService implements StockUpdateService {

    private final OptimisticStockRepository repo;

    public OptimisticStockUpdateService(OptimisticStockRepository repo) {
        this.repo = repo;
    }

    @Override
    public String lockType() {
        return "optimistic";
    }

    @Override
    @Transactional
    public boolean tryReserveOne(Long stockId) {
        Stock stock = repo.find(stockId).orElseThrow();
        if (stock.getQuantity() <= 0) return false;

        stock.decrementOne();
        try {
            repo.save(stock);
            return true;
        } catch (ObjectOptimisticLockingFailureException e) {
            throw e;
        }
    }
}