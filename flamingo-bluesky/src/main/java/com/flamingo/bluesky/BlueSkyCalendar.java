package com.flamingo.bluesky;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * P6a blue-sky filing calendar (§13a): state notice-filing matrix as DATA
 * (per-state deadlines from offering qualification date). Reuses the rule-pack
 * pattern — states are data rows, the generator is generic.
 */
public final class BlueSkyCalendar {

    /** Per-state notice-filing rule: days-from-qualification, form name. */
    public record StateRule(String stateCode, String filingType, int daysFromQualification) {}

    /** Generated calendar entry for one state. */
    public record CalendarEntry(String stateCode, String filingType,
                                LocalDate dueDate, String status) {}

    /** Common state-notice rules (Form D states + notice states), data-driven. */
    public static final List<StateRule> DEFAULT_MATRIX = List.of(
            new StateRule("NY", "Notice Filing", 30),
            new StateRule("CA", "Notice Filing", 15),
            new StateRule("TX", "Notice Filing", 11),
            new StateRule("FL", "Notice Filing", 20),
            new StateRule("IL", "Notice Filing", 30),
            new StateRule("MA", "Notice Filing", 30),
            new StateRule("WA", "Notice Filing", 15),
            new StateRule("CO", "Notice Filing", 30));

    private BlueSkyCalendar() {}

    /** Generates due-dates for all states from the qualification date. Deterministic. */
    public static List<CalendarEntry> generate(LocalDate qualificationDate,
                                               List<String> chosenStates,
                                               Map<String, LocalDate> alreadyFiled) {
        List<CalendarEntry> out = new ArrayList<>();
        for (StateRule rule : DEFAULT_MATRIX) {
            if (chosenStates != null && !chosenStates.isEmpty()
                    && !chosenStates.contains(rule.stateCode())) {
                continue;
            }
            LocalDate due = qualificationDate.plusDays(rule.daysFromQualification());
            LocalDate filed = alreadyFiled.get(rule.stateCode());
            String status = filed != null ? "filed"
                    : (due.isBefore(LocalDate.now()) ? "overdue" : "planned");
            out.add(new CalendarEntry(rule.stateCode(), rule.filingType(), due, status));
        }
        return out;
    }
}
