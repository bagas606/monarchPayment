package id.ppob2.order.repository;

import id.ppob2.order.domain.ChildOrder;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChildOrderRepository extends JpaRepository<ChildOrder, Long> {
    List<ChildOrder> findByParentOrderIdOrderBySequenceNo(Long parentOrderId);
}
