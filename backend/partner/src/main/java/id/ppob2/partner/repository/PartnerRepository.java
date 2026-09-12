package id.ppob2.partner.repository;

import id.ppob2.partner.domain.Partner;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PartnerRepository extends JpaRepository<Partner, Long> {
    Optional<Partner> findByCode(String code);
}
