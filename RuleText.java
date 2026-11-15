package clre20.itemReplace.util;

import clre20.itemReplace.config.Messages;
import clre20.itemReplace.model.Rule;
import net.kyori.adventure.text.Component;

/** 規則在列表與介面上共用的文字。 */
public final class RuleText {

    private RuleText() {
    }

    public static Component status(Messages messages, Rule rule) {
        if (rule == null || !rule.enabled()) {
            return messages.get("status.disabled");
        }
        if (rule.conflictWith() != null) {
            return messages.get("status.conflict");
        }
        return messages.get("status.enabled");
    }

    public static Component replacements(Messages messages, Rule rule) {
        if (rule.replacements().isEmpty()) {
            return messages.get("replacements.none");
        }
        return messages.get("replacements.count", "count", rule.replacements().size());
    }
}
