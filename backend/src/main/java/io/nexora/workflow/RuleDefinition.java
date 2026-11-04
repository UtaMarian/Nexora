package io.nexora.workflow;

import java.util.List;

/**
 * What a rule does, stored as JSON on the rule.
 *
 * @param trigger    CREATED, CHANGED, APPROVED, REJECTED or DATE
 * @param dateColumn for DATE: the column to watch
 * @param daysBefore for DATE: how many days before that date to fire; 0 is the day itself
 */
public record RuleDefinition(String trigger, String dateColumn, Integer daysBefore, List<Condition> conditions, List<Action> actions) {

    /** The same operators as saved list views. {@code column} is a column's internal name or a built-in. */
    public record Condition(String column, String operator, String value) {}

    /** Someone a rule refers to. Named, not by id, so rules survive export to another farm. */
    public record Who(String kind, String login, String displayName, String column) {
        public static final String PERSON = "PERSON";
        public static final String CREATOR = "CREATOR";
        public static final String EDITOR = "EDITOR";
        public static final String COLUMN = "COLUMN";
    }

    /**
     * @param type one of SEND_EMAIL, SET_VALUE, REQUEST_APPROVAL, REQUEST_REVIEW, MOVE_TO_FOLDER, CREATE_ITEM
     * @param folder for MOVE_TO_FOLDER: a folder in the same library, created if missing
     * @param list   for CREATE_ITEM: the name of a list in the same site
     */
    public record Action(String type, List<Who> people, String subject, String body, String column, String value, String mode,
                         Integer dueDays, String folder, String list, String title) {}
}
