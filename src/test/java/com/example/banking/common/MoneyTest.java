package com.example.banking.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class MoneyTest {

    @Test
    void normalisesAmountsToTwoDecimalPlaces() {
        assertThat(Money.of("12.5")).isEqualTo(new BigDecimal("12.50"));
        assertThat(Money.of(BigDecimal.TEN)).isEqualTo(new BigDecimal("10.00"));
    }

    @Test
    void rejectsAmountsThatWouldNeedRounding() {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.of("0.001"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "0.00", "-0.01"})
    void positiveAmountsMustBeGreaterThanZero(String amount) {
        assertThatIllegalArgumentException().isThrownBy(() -> Money.positive(new BigDecimal(amount)));
    }
}
