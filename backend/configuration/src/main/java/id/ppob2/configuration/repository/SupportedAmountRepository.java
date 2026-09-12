package id.ppob2.configuration.repository;

import id.ppob2.configuration.domain.SupportedAmount;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupportedAmountRepository extends JpaRepository<SupportedAmount, Long> {
    List<SupportedAmount> findByProductCategoryAndStatusOrderByAmountAsc(String productCategory, String status);
}
