package id.ppob2.order;

import id.ppob2.order.domain.ParentOrder;
import id.ppob2.order.repository.ParentOrderRepository;
import id.ppob2.sharedkernel.error.ApiException;
import id.ppob2.sharedkernel.error.ErrorCode;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns idempotency (`parent_order_idem_uk`, Section 22.15) and initial persistence, in its own
 * transaction so it commits independently of whatever the payment gateway does next — Section
 * 25.2 requires a gateway failure to leave a {@code CREATED} order behind, not roll it back.
 *
 * <p>Note what the {@code existing.isPresent()} branch does <em>not</em> do: it returns the stored
 * row and writes nothing to it. So a replay carrying a different {@code callback_url} or
 * {@code metadata} leaves both as the first accepted request set them. That is deliberate — see
 * {@link ParentOrder#matchesRequest} for the argument, which is also why neither field takes part
 * in the conflict check above it.
 */
@Service
public class ParentOrderCreationService {

    private final ParentOrderRepository repository;
    private final OrderNumberGenerator orderNumberGenerator;

    public ParentOrderCreationService(ParentOrderRepository repository, OrderNumberGenerator orderNumberGenerator) {
        this.repository = repository;
        this.orderNumberGenerator = orderNumberGenerator;
    }

    @Transactional
    public OrderCreationOutcome createOrGetExisting(CreateOrderCommand command) {
        var existing = repository.findByClientIdAndIdempotencyKey(
                command.channelContext().clientId(), command.idempotencyKey());

        if (existing.isPresent()) {
            ParentOrder order = existing.get();
            if (!order.matchesRequest(command.productId(), command.parentAmount(), command.customerReference())) {
                throw new ApiException(ErrorCode.IDEMPOTENCY_KEY_CONFLICT,
                        "Idempotency-Key already used with a different request payload.");
            }
            return new OrderCreationOutcome(order, false);
        }

        ParentOrder order = new ParentOrder(
                orderNumberGenerator.next(),
                command.channelId(),
                command.partnerId(),
                command.channelContext().clientId(),
                command.productId(),
                command.parentAmount(),
                command.channelContext().channelType().name(),
                command.idempotencyKey(),
                command.customerReference(),
                Instant.now(),
                command.callbackUrl(),
                command.metadata());

        return new OrderCreationOutcome(repository.save(order), true);
    }
}
