package com.alexbank.aml.casemanagement.domain;

import com.alexbank.aml.casemanagement.domain.exception.IllegalCaseTransitionException;
import com.alexbank.aml.casemanagement.domain.model.Case;
import com.alexbank.aml.casemanagement.domain.model.CaseStatus;
import com.alexbank.aml.casemanagement.domain.model.RiskScore;
import net.jqwik.api.Arbitraries;
import net.jqwik.api.Arbitrary;
import net.jqwik.api.ForAll;
import net.jqwik.api.Property;
import net.jqwik.api.Provide;
import net.jqwik.api.constraints.AlphaChars;
import net.jqwik.api.constraints.IntRange;
import net.jqwik.api.constraints.StringLength;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Property-based tests prove invariants hold for *any* input,
 * not just the examples we hand-wrote. Mutation testing (Pitest)
 * + property tests is the senior-level quality combo.
 */
class CasePropertiesTest {

    @Property
    void any_valid_case_is_created_in_OPEN_status(
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String alertId,
            @ForAll @AlphaChars @StringLength(min = 1, max = 30) String customerId,
            @ForAll @IntRange(min = 0, max = 100) int riskScore
    ) {
        Case c = Case.open(alertId, customerId, new RiskScore(riskScore));
        assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
        assertThat(c.riskScore().value()).isEqualTo(riskScore);
    }

    @Property
    void closed_cases_are_terminal(@ForAll("validCases") Case c) {
        c.close("test resolution");

        assertThatThrownBy(() -> c.assignTo("inv-x"))
                .isInstanceOf(IllegalCaseTransitionException.class);
        assertThatThrownBy(() -> c.escalate("test"))
                .isInstanceOf(IllegalCaseTransitionException.class);
        assertThatThrownBy(() -> c.close("test"))
                .isInstanceOf(IllegalCaseTransitionException.class);
    }

    @Property
    void every_state_change_emits_at_least_one_event(@ForAll("validCases") Case c) {
        c.pullDomainEvents();

        c.assignTo("inv-1");
        assertThat(c.pullDomainEvents()).isNotEmpty();
    }

    @Property
    void score_outside_range_is_always_rejected(@ForAll int outOfRange) {
        if (outOfRange >= 0 && outOfRange <= 100) return;
        assertThatThrownBy(() -> new RiskScore(outOfRange))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Provide
    Arbitrary<Case> validCases() {
        return Arbitraries.of(
                Case.open("a1", "c1", new RiskScore(50)),
                Case.open("a2", "c2", new RiskScore(80))
        );
    }
}
