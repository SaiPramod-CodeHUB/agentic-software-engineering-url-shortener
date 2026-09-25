package com.agentsdlc.orchestrator.approval;

import java.util.Map;

/** Source of human answers to clarifying questions about an ambiguous request. */
@FunctionalInterface
public interface ClarificationProvider {

    /**
     * Answers clarifying questions.
     *
     * @param questions questions by id
     * @return answers by question id
     */
    Map<String, String> answer(Map<String, String> questions);
}
