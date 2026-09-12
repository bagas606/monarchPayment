package id.ppob2.configuration;

import id.ppob2.configuration.domain.SupportedAmount;
import id.ppob2.configuration.repository.SupportedAmountRepository;
import id.ppob2.sharedkernel.money.Money;
import java.util.List;
import org.springframework.stereotype.Service;

/** PRD Section 23.3 (Get Supported Amounts) and 21.2 — supported amounts are stored data,
 * never a computed tier/step formula, so a category's diverging config never needs a code change. */
@Service
public class SupportedAmountQueryService {

    private final SupportedAmountRepository repository;

    public SupportedAmountQueryService(SupportedAmountRepository repository) {
        this.repository = repository;
    }

    public List<Money> activeAmountsForCategory(String productCategory) {
        return repository.findByProductCategoryAndStatusOrderByAmountAsc(productCategory, "ACTIVE")
                .stream()
                .map(SupportedAmount::getAmount)
                .toList();
    }
}
