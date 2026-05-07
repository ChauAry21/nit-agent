package dev.aryan.nitagent.agent.review;

public enum ReviewSection {
    SECURITY("SECURITY & EDGE CASES"),
    CODE_QUALITY("CODE QUALITY & READABILITY"),
    PERFORMANCE("PERFORMANCE"),
    TEST_COVERAGE("TEST COVERAGE"),
    SUMMARY("SUMMARY");

    public final String header;

    ReviewSection(String header) {
        this.header = header;
    }

    public static String buildHeaderList() {
        StringBuilder sb = new StringBuilder();
        for (ReviewSection s : values()) {
            sb.append(s.header).append("\n");
        }
        return sb.toString().trim();
    }
}