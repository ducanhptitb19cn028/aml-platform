package com.yourbank.aml.casemanagement.domain;

import com.yourbank.aml.casemanagement.domain.event.CaseAssigned;
import com.yourbank.aml.casemanagement.domain.event.CaseClosed;
import com.yourbank.aml.casemanagement.domain.event.CaseEscalated;
import com.yourbank.aml.casemanagement.domain.event.CaseOpened;
import com.yourbank.aml.casemanagement.domain.exception.IllegalCaseTransitionException;
import com.yourbank.aml.casemanagement.domain.model.Case;
import com.yourbank.aml.casemanagement.domain.model.CaseStatus;
import com.yourbank.aml.casemanagement.domain.model.RiskScore;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Pure unit tests for the Case aggregate.
 *
 * NO @SpringBootTest, NO Testcontainers, NO @ExtendWith(MockitoExtension.class).
 * These tests run in milliseconds because the domain has no infrastructure.
 * That speed is what makes meaningful TDD sustainable over the 3+ years of a PhD.
 */
class CaseAggregateTest {

    private static RiskScore score(int s) { return new RiskScore(s); }

    @Nested
    @DisplayName("Opening a case")
    class Opening {

        @Test
        void should_create_case_in_open_status() {
            Case c = Case.open("alert-123", "cust-7", score(85));

            assertThat(c.status()).isEqualTo(CaseStatus.OPEN);
            assertThat(c.alertId()).isEqualTo("alert-123");
            assertThat(c.customerId()).isEqualTo("cust-7");
            assertThat(c.riskScore().value()).isEqualTo(85);
            assertThat(c.assignedInvestigator()).isEmpty();
        }

        @Test
        void should_emit_CaseOpened_event() {
            Case c = Case.open("alert-123", "cust-7", score(85));

            List<?> events = c.peekDomainEvents();
            assertThat(events).hasSize(1).first().isInstanceOf(CaseOpened.class);

            CaseOpened event = (CaseOpened) events.get(0);
            assertThat(event.alertId()).isEqualTo("alert-123");
            assertThat(event.riskScore()).isEqualTo(85);
        }

        @Test
        void should_reject_blank_alert_id() {
            assertThatThrownBy(() -> Case.open("", "cust-7", score(50)))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_risk_score_above_100() {
            assertThatThrownBy(() -> new RiskScore(101))
                    .isInstanceOf(IllegalArgumentException.class);
        }

        @Test
        void should_reject_negative_risk_score() {
            assertThatThrownBy(() -> new RiskScore(-1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Assigning investigators")
    class Assigning {

        @Test
        void should_move_case_from_open_to_under_investigation() {
            Case c = Case.open("alert-1", "cust-1", score(60));
            c.pullDomainEvents();

            c.assignTo("inv-42");

            assertThat(c.status()).isEqualTo(CaseStatus.UNDER_INVESTIGATION);
            assertThat(c.assignedInvestigator()).contains("inv-42");
        }

        @Test
        void should_emit_CaseAssigned_event() {
            Case c = Case.open("alert-1", "cust-1", score(60));
            c.pullDomainEvents();

            c.assignTo("inv-42");

            assertThat(c.peekDomainEvents())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(CaseAssigned.class);
        }

        @Test
        void should_allow_reassignment_without_status_change() {
            Case c = Case.open("alert-1", "cust-1", score(60));
            c.assignTo("inv-1");
            c.pullDomainEvents();

            c.assignTo("inv-2");

            assertThat(c.assignedInvestigator()).contains("inv-2");
            assertThat(c.status()).isEqualTo(CaseStatus.UNDER_INVESTIGATION);
        }

        @Test
        void should_reject_assignment_to_closed_case() {
            Case c = Case.open("alert-1", "cust-1", score(60));
            c.assignTo("inv-1");
            c.close("not suspicious");

            assertThatThrownBy(() -> c.assignTo("inv-2"))
                    .isInstanceOf(IllegalCaseTransitionException.class);
        }
    }

    @Nested
    @DisplayName("Escalation")
    class Escalation {

        @Test
        void should_escalate_from_under_investigation() {
            Case c = Case.open("alert-1", "cust-1", score(90));
            c.assignTo("inv-1");
            c.pullDomainEvents();

            c.escalate("structuring detected");

            assertThat(c.status()).isEqualTo(CaseStatus.ESCALATED);
            assertThat(c.peekDomainEvents())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(CaseEscalated.class);
        }

        @Test
        void should_reject_escalation_directly_from_open() {
            Case c = Case.open("alert-1", "cust-1", score(90));

            assertThatThrownBy(() -> c.escalate("smurfing"))
                    .isInstanceOf(IllegalCaseTransitionException.class);
        }

        @Test
        void should_reject_escalation_with_blank_reason() {
            Case c = Case.open("alert-1", "cust-1", score(90));
            c.assignTo("inv-1");

            assertThatThrownBy(() -> c.escalate(" "))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Closing")
    class Closing {

        @Test
        void should_close_from_open() {
            Case c = Case.open("alert-1", "cust-1", score(30));
            c.pullDomainEvents();

            c.close("false positive");

            assertThat(c.status()).isEqualTo(CaseStatus.CLOSED);
            assertThat(c.peekDomainEvents())
                    .hasSize(1)
                    .first()
                    .isInstanceOf(CaseClosed.class);
        }

        @Test
        void should_close_from_escalated() {
            Case c = Case.open("alert-1", "cust-1", score(95));
            c.assignTo("inv-1");
            c.escalate("clear ML pattern");

            c.close("SAR filed");

            assertThat(c.status()).isEqualTo(CaseStatus.CLOSED);
        }

        @Test
        void should_reject_reopening_a_closed_case() {
            Case c = Case.open("alert-1", "cust-1", score(50));
            c.close("not suspicious");

            assertThatThrownBy(() -> c.escalate("changed my mind"))
                    .isInstanceOf(IllegalCaseTransitionException.class);
        }
    }

    @Nested
    @DisplayName("Domain event handling")
    class Events {

        @Test
        void pullDomainEvents_should_clear_buffer() {
            Case c = Case.open("alert-1", "cust-1", score(50));

            assertThat(c.pullDomainEvents()).hasSize(1);
            assertThat(c.peekDomainEvents()).isEmpty();
        }
    }
}
