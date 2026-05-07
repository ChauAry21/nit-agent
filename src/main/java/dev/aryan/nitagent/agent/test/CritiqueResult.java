package dev.aryan.nitagent.agent.test;

public enum CritiqueResult {
    NO_ISSUES("no issues"),
    LOOKS_GOOD("looks good"),
    NO_HALLUCINATIONS("no hallucinations"),
    NO_PROBLEMS("no problems");

    public final String phrase;

    CritiqueResult(String phrase) { this.phrase = phrase; }

    public static boolean isClean(String critique) {
        String lower = critique.toLowerCase();
        for (CritiqueResult r : values()) {
            if (lower.contains(r.phrase)) return true;
        }
        return false;
    }
}
