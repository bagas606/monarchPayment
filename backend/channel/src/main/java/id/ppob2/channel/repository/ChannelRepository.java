package id.ppob2.channel.repository;

import id.ppob2.channel.domain.Channel;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChannelRepository extends JpaRepository<Channel, Long> {
    Optional<Channel> findByCode(String code);
}
