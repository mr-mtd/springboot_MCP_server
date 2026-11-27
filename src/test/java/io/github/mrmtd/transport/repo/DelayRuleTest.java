package io.github.mrmtd.transport.repo;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DelayRuleTest {

    @Test
    void lateIsStrictlyMoreThanTwoHoursOnTheRawValue() {
        assertThat(DelayRule.isLate(null)).isFalse();
        assertThat(DelayRule.isLate(2.0)).isFalse();
        assertThat(DelayRule.isLate(2.0001)).isTrue();
        // 2.04 shows as 2.0 but is still late: the decision uses the raw value, not the displayed one.
        assertThat(DelayRule.isLate(2.04)).isTrue();
        assertThat(DelayRule.display(2.04)).isEqualTo(2.0);
    }

    @Test
    void displayRoundsHalfUpToOneDecimal() {
        assertThat(DelayRule.display(3.25)).isEqualTo(3.3);
        assertThat(DelayRule.display(-0.04)).isEqualTo(-0.0);
        assertThat(DelayRule.display((Double) null)).isNull();
    }
}
