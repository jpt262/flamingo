package com.flamingo.drafting;

import java.time.LocalDate;
import java.util.Map;
import java.util.Set;

/**
 * §10 compliance-mode machine (tenant config state):
 * Idle→Drafting→Runway→LOCKOUT→Priced→AllocatedToClose→Idle(purge).
 *
 * <p>Runway triggers on detected draft registration statement in the issuer's
 * own filings or operator flag. During LOCKOUT the drafter REFUSES
 * outbound-facing generation classes (config allowlist-enforced). Cold start
 * = LOCKOUT-adjacent conservative defaults per tenant.</p>
 */
public class ComplianceModeMachine {

    public enum Mode { IDLE, DRAFTING, RUNWAY, LOCKOUT, PRICED, ALLOCATED_TO_CLOSE }

    /** Generation classes; outbound-facing ones require non-LOCKOUT mode. */
    public enum GenerationClass {
        CITED_FACT_TABLE,      // always allowed — pure data
        SECTION_TEMPLATE,      // allowed unless LOCKOUT
        NARRATIVE_DRAFT,       // outbound-facing class
        DEAL_SHEET_EXPORT      // outbound-facing class
    }

    public sealed interface Event permits Advance, OperatorFlag, RegistrationDetected, Reset {}
    public record Advance(Mode to) implements Event {}
    public record OperatorFlag(String reason) implements Event {}
    public record RegistrationDetected(String accession) implements Event {}
    public record Reset() implements Event {}

    private static final Map<Mode, Set<Mode>> LEGAL = Map.of(
            Mode.IDLE, Set.of(Mode.DRAFTING),
            Mode.DRAFTING, Set.of(Mode.RUNWAY, Mode.LOCKOUT, Mode.IDLE),
            Mode.RUNWAY, Set.of(Mode.LOCKOUT, Mode.PRICED, Mode.IDLE),
            Mode.LOCKOUT, Set.of(Mode.PRICED, Mode.IDLE),
            Mode.PRICED, Set.of(Mode.ALLOCATED_TO_CLOSE),
            Mode.ALLOCATED_TO_CLOSE, Set.of(Mode.IDLE));

    private final Set<GenerationClass> outboundAllowlistWhenLocked;

    public ComplianceModeMachine(Set<GenerationClass> outboundAllowlistWhenLocked) {
        this.outboundAllowlistWhenLocked = Set.copyOf(outboundAllowlistWhenLocked);
    }

    /** Cold start = LOCKOUT-adjacent conservative default: DRAFTING but with
     *  outbound classes refused until mode proves itself. */
    public Mode coldStart() {
        return Mode.DRAFTING;
    }

    public Mode transition(Mode current, Event event) {
        if (event instanceof Reset) {
            return Mode.IDLE;
        }
        if (event instanceof RegistrationDetected) {
            // §10: draft registration in issuer's own filings triggers RUNWAY automatically
            return requireLegal(current, Mode.RUNWAY);
        }
        if (event instanceof Advance) {
            return requireLegal(current, ((Advance) event).to());
        }
        return current; // OperatorFlag is informational
    }

    /** The §10 enforcement point: LOCKOUT refuses outbound-facing classes. */
    public boolean mayGenerate(Mode current, GenerationClass cls) {
        if (cls == GenerationClass.CITED_FACT_TABLE) {
            return true; // pure data, always
        }
        if (current == Mode.LOCKOUT) {
            return outboundAllowlistWhenLocked.contains(cls);
        }
        return true;
    }

    private static Mode requireLegal(Mode from, Mode to) {
        if (!LEGAL.getOrDefault(from, Set.of()).contains(to)) {
            throw new IllegalStateException(
                    "ILLEGAL compliance-mode transition %s → %s (§10 machine)".formatted(from, to));
        }
        return to;
    }
}
