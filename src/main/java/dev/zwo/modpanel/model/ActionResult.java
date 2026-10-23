package dev.zwo.modpanel.model;

import java.util.List;

public record ActionResult(boolean success, String message, List<String> warnings, int affected) {

    public ActionResult {
        warnings = warnings == null ? List.of() : List.copyOf(warnings);
    }

    public static ActionResult ok(String message) {
        return new ActionResult(true, message, List.of(), 0);
    }

    public static ActionResult ok(String message, int affected) {
        return new ActionResult(true, message, List.of(), affected);
    }

    public static ActionResult ok(String message, List<String> warnings, int affected) {
        return new ActionResult(true, message, warnings, affected);
    }

    public static ActionResult fail(String message) {
        return new ActionResult(false, message, List.of(), 0);
    }
}
