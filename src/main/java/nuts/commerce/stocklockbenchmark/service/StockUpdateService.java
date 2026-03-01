package nuts.commerce.stocklockbenchmark.service;

public interface StockUpdateService {
    String lockType();
    boolean tryReserveOne(Long stockId);
}