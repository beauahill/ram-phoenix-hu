package com.ramphoenix.launcher;

/** Shared validation for form fields and persisted data. */
final class MaintenanceInput {
    static final int MAX_MILES=3_000_000, MAX_INTERVAL=1_000_000, MAX_MONTHS=240;
    private MaintenanceInput() {}
    static int number(String text,String label,int empty,int maximum) {
        String value=text.trim();
        if(value.isEmpty()) return empty;
        if(!value.matches("(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)"))
            throw new IllegalArgumentException(label+": enter a whole number");
        try {
            long parsed=Long.parseLong(value.replace(",",""));
            if(parsed>maximum) throw new NumberFormatException();
            return (int)parsed;
        } catch(NumberFormatException e) { throw new IllegalArgumentException(label+": number is too large"); }
    }
    static String name(String text) {
        String value=text.trim();
        if(value.isEmpty()) throw new IllegalArgumentException("Enter a service name");
        if(value.length()>100) throw new IllegalArgumentException("Use a service name under 100 characters");
        return value;
    }
}
