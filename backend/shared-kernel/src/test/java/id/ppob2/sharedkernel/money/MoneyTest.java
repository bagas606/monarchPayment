package id.ppob2.sharedkernel.money;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class MoneyTest {

    @Test
    void addsAndSubtractsWholeRupiah() {
        Money a = Money.of(100_000L);
        Money b = Money.of(30_000L);

        assertThat(a.add(b)).isEqualTo(Money.of(130_000L));
        assertThat(a.subtract(b)).isEqualTo(Money.of(70_000L));
    }

    @Test
    void comparesByValueNotIdentity() {
        assertThat(Money.of(50_000L)).isEqualTo(Money.of(50_000L));
        assertThat(Money.of(50_000L).isGreaterThan(Money.of(10_000L))).isTrue();
    }
}
