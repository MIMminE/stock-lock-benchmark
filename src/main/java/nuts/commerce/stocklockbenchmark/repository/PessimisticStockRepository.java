package nuts.commerce.stocklockbenchmark.repository;

import jakarta.persistence.LockModeType;
import nuts.commerce.stocklockbenchmark.domain.Stock;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;

import java.util.Optional;

public interface PessimisticStockRepository extends Repository<Stock, Long> {

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select s from Stock s where s.id = :id")
    Optional<Stock> findByIdForUpdate(Long id);

    Stock save(Stock stock);
}