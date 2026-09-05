package ru.milkyway.plugmanreloaded.commands;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class CommandFlag {

    private final String name;
    private final List<String> forms;
    private final boolean takesValue;

    public CommandFlag(String name, List<String> forms, boolean takesValue) {
        this.name = name;
        this.forms = List.copyOf(forms);
        this.takesValue = takesValue;
    }

    public static CommandFlag of(String name, String... forms) {
        return new CommandFlag(name, List.of(forms), false);
    }

    public static CommandFlag valueOption(String name, String... forms) {
        return new CommandFlag(name, List.of(forms), true);
    }

    public String name() {
        return name;
    }

    public List<String> forms() {
        return forms;
    }

    public boolean takesValue() {
        return takesValue;
    }

    public boolean matches(String token) {
        if (token == null) {
            return false;
        }
        for (String form : forms) {
            if (form.equalsIgnoreCase(token)) {
                return true;
            }
        }
        return false;
    }

    public boolean isUsed(Set<String> usedTokens) {
        for (String form : forms) {
            if (usedTokens.contains(form.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    public List<String> suggestUnused(Set<String> usedTokens) {
        if (isUsed(usedTokens)) {
            return Collections.emptyList();
        }
        return forms;
    }
}
