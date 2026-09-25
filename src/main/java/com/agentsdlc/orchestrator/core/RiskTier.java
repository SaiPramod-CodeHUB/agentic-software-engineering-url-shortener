package com.agentsdlc.orchestrator.core;

/**
 * How much damage a task can do if it goes wrong. Gates key off the tier:
 * {@link #HIGH} work needs a human, and destructive work may never be
 * declared {@link #LOW}.
 */
public enum RiskTier {
    /** Read-only or trivially reversible work (analysis, docs). */
    LOW,
    /** Changes code or data but is covered by tests and rollback. */
    MEDIUM,
    /** Production-affecting or hard to reverse; requires human approval. */
    HIGH
}
