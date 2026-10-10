package id.ppob1.sim.repository;

import id.ppob1.sim.domain.InboundWebhook;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface InboundWebhookRepository extends JpaRepository<InboundWebhook, Long> {

    boolean existsByNonce(String nonce);

    List<InboundWebhook> findAllByOrderByIdDesc();

    List<InboundWebhook> findByPpob2OrderIdOrderByIdDesc(String ppob2OrderId);
}
