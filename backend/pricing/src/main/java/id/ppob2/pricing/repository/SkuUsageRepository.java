package id.ppob2.pricing.repository;

import id.ppob2.pricing.domain.SkuUsage;
import java.time.LocalDate;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SkuUsageRepository extends JpaRepository<SkuUsage, Long> {

    List<SkuUsage> findByProviderSkuIdInAndUsageDate(List<Long> providerSkuIds, LocalDate usageDate);

    @Modifying
    @Query(value = """
            INSERT INTO sku_usage (provider_sku_id, usage_date, daily_usage, lifetime_usage)
            VALUES (:providerSkuId, :usageDate, :quantity, :quantity)
            ON CONFLICT (provider_sku_id, usage_date)
            DO UPDATE SET daily_usage = sku_usage.daily_usage + :quantity, lifetime_usage = sku_usage.lifetime_usage + :quantity
            """, nativeQuery = true)
    void increment(@Param("providerSkuId") Long providerSkuId, @Param("usageDate") LocalDate usageDate, @Param("quantity") int quantity);
}
