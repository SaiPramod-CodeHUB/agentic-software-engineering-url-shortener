package com.agentsdlc.orchestrator.core;

/**
 * Well-known task tags. Gates select the tasks they govern by tag, so a new
 * policy is attached by tagging tasks rather than editing the engine.
 */
public final class Tags {

    /** Production release: change control plus final human sign-off. */
    public static final String RELEASE = "release";
    /** Needs a human approval even below {@link RiskTier#HIGH}. */
    public static final String NEEDS_APPROVAL = "needs-approval";
    /** Must leave {@code tests.run > 0} and {@code tests.failed == 0} in its namespace. */
    public static final String TESTS_MUST_PASS = "tests-must-pass";
    /** May only start once the spec is {@code READY} (not a draft). */
    public static final String REQUIRES_READY_SPEC = "requires-ready-spec";
    /** Brownfield change: may only start once an impact analysis exists. */
    public static final String REQUIRES_IMPACT_ANALYSIS = "requires-impact-analysis";

    private Tags() {
    }
}
