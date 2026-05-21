package com.alexbank.aml.kyc.domain;

import com.alexbank.aml.kyc.domain.event.CustomerOnboarded;
import com.alexbank.aml.kyc.domain.event.CustomerRejected;
import com.alexbank.aml.kyc.domain.event.CustomerRiskUpdated;
import com.alexbank.aml.kyc.domain.event.CustomerVerified;
import com.alexbank.aml.kyc.domain.exception.IllegalVerificationTransitionException;
import com.alexbank.aml.kyc.domain.model.CountryCode;
import com.alexbank.aml.kyc.domain.model.Customer;
import com.alexbank.aml.kyc.domain.model.CustomerId;
import com.alexbank.aml.kyc.domain.model.RiskProfile;
import com.alexbank.aml.kyc.domain.model.RiskTier;
import com.alexbank.aml.kyc.domain.model.VerificationStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CustomerAggregateTest {

    private static final CountryCode GB = new CountryCode("GB");

    @Nested
    @DisplayName("Onboarding")
    class Onboarding {

        @Test
        void creates_customer_in_PENDING_status_with_STANDARD_tier() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Alice Smith", GB);
            assertThat(c.status()).isEqualTo(VerificationStatus.PENDING);
            assertThat(c.riskProfile().tier()).isEqualTo(RiskTier.STANDARD);
            assertThat(c.riskProfile().residencyCountry()).isEqualTo(GB);
        }

        @Test
        void emits_CustomerOnboarded_event() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Alice", GB);
            assertThat(c.peekDomainEvents()).hasSize(1)
                    .first().isInstanceOf(CustomerOnboarded.class);
        }

        @Test
        void rejects_blank_legal_name() {
            assertThatThrownBy(() -> Customer.onboard(CustomerId.of("c"), "", GB))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Verification lifecycle")
    class Lifecycle {

        @Test
        void PENDING_to_IN_PROGRESS_to_VERIFIED() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Bob", GB);
            c.startVerification();
            assertThat(c.status()).isEqualTo(VerificationStatus.IN_PROGRESS);
            c.verify("officer-7");
            assertThat(c.status()).isEqualTo(VerificationStatus.VERIFIED);
            assertThat(c.isVerified()).isTrue();
        }

        @Test
        void verified_emits_CustomerVerified() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Bob", GB);
            c.startVerification();
            c.pullDomainEvents();
            c.verify("officer-7");
            assertThat(c.peekDomainEvents())
                    .anyMatch(e -> e instanceof CustomerVerified);
        }

        @Test
        void cannot_skip_IN_PROGRESS() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Bob", GB);
            assertThatThrownBy(() -> c.verify("officer-7"))
                    .isInstanceOf(IllegalVerificationTransitionException.class);
        }

        @Test
        void rejection_is_terminal() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Eve", GB);
            c.reject("forged documents");
            assertThat(c.status().isTerminal()).isTrue();
            assertThatThrownBy(() -> c.startVerification())
                    .isInstanceOf(IllegalVerificationTransitionException.class);
        }

        @Test
        void rejection_emits_CustomerRejected_with_reason() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Eve", GB);
            c.pullDomainEvents();
            c.reject("identity mismatch");
            assertThat(c.peekDomainEvents())
                    .hasSize(1)
                    .first().isInstanceOf(CustomerRejected.class);
        }

        @Test
        void rejection_requires_reason() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Eve", GB);
            assertThatThrownBy(() -> c.reject(""))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Risk profile updates")
    class RiskUpdates {

        @Test
        void noop_update_emits_no_event() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Diana", GB);
            c.pullDomainEvents();
            c.updateRiskProfile(RiskProfile.standard(GB), "annual review unchanged");
            assertThat(c.peekDomainEvents()).isEmpty();
        }

        @Test
        void tier_change_emits_CustomerRiskUpdated() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Diana", GB);
            c.pullDomainEvents();
            c.updateRiskProfile(
                    new RiskProfile(RiskTier.HIGH, true, false, GB),
                    "PEP designation");
            assertThat(c.peekDomainEvents())
                    .hasSize(1)
                    .first().isInstanceOf(CustomerRiskUpdated.class);
        }

        @Test
        void event_carries_previous_and_new_tier() {
            Customer c = Customer.onboard(CustomerId.of("c1"), "Diana", GB);
            c.pullDomainEvents();
            c.updateRiskProfile(
                    new RiskProfile(RiskTier.HIGH, false, false, GB),
                    "elevated risk");
            CustomerRiskUpdated e = (CustomerRiskUpdated) c.peekDomainEvents().get(0);
            assertThat(e.previousTier()).isEqualTo(RiskTier.STANDARD);
            assertThat(e.newTier()).isEqualTo(RiskTier.HIGH);
            assertThat(e.tierChanged()).isTrue();
        }

        @Test
        void sanctioned_must_be_PROHIBITED() {
            assertThatThrownBy(() ->
                    new RiskProfile(RiskTier.HIGH, false, true, GB))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void sanctioned_at_PROHIBITED_is_allowed() {
            RiskProfile rp = new RiskProfile(RiskTier.PROHIBITED, false, true, GB);
            assertThat(rp.sanctioned()).isTrue();
        }
    }

    @Nested
    @DisplayName("Risk tier helpers")
    class TierHelpers {

        @Test
        void HIGH_and_PROHIBITED_require_EDD() {
            assertThat(RiskTier.HIGH.requiresEnhancedDueDiligence()).isTrue();
            assertThat(RiskTier.PROHIBITED.requiresEnhancedDueDiligence()).isTrue();
        }

        @Test
        void STANDARD_and_LOW_do_not_require_EDD() {
            assertThat(RiskTier.STANDARD.requiresEnhancedDueDiligence()).isFalse();
            assertThat(RiskTier.LOW.requiresEnhancedDueDiligence()).isFalse();
        }
    }
}
