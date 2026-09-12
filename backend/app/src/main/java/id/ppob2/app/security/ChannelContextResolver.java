package id.ppob2.app.security;

import id.ppob2.channel.repository.ChannelRepository;
import id.ppob2.partner.domain.ApiClient;
import id.ppob2.partner.domain.Partner;
import id.ppob2.partner.repository.PartnerRepository;
import id.ppob2.sharedkernel.channel.ChannelContext;
import id.ppob2.sharedkernel.channel.ChannelType;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Composes the `channel`, `partner` and `api_client` registries into a {@link ChannelContext}.
 * This composition intentionally lives in the app/channel-adapter layer (Section 19's
 * "API / Channel Adapters" box) rather than inside the `channel` module itself, because Section
 * 20.2's allowed-dependency graph does not grant `channel` a compile dependency on `partner` —
 * only the composition root (`app`, which already depends on both) may join them.
 */
@Component
public class ChannelContextResolver {

    private final ChannelRepository channelRepository;
    private final PartnerRepository partnerRepository;

    public ChannelContextResolver(ChannelRepository channelRepository, PartnerRepository partnerRepository) {
        this.channelRepository = channelRepository;
        this.partnerRepository = partnerRepository;
    }

    public ChannelContext resolve(ApiClient apiClient) {
        Partner partner = partnerRepository.findById(apiClient.getPartnerId())
                .orElseThrow(() -> new IllegalStateException("api_client references unknown partner_id=" + apiClient.getPartnerId()));

        ChannelType channelType = Optional.ofNullable(partner.getChannelId())
                .flatMap(channelRepository::findById)
                .map(channel -> mapChannelCode(channel.getCode()))
                .orElse(ChannelType.RESELLER_API);

        return new ChannelContext(
                channelType,
                partner.getCode(),
                apiClient.getClientId(),
                null,
                apiClient.getClientId(),
                null
        );
    }

    private ChannelType mapChannelCode(String code) {
        try {
            return ChannelType.valueOf(code);
        } catch (IllegalArgumentException e) {
            return ChannelType.RESELLER_API;
        }
    }
}
