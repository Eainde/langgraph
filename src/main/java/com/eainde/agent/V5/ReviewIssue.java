package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single compliance issue identified by the extraction critic agent.
 *
 * @param ruleId           the rule that was violated (e.g., "RC2", "JT.6", "C3.1")
 * @param severity         "critical", "major", or "minor"
 * @param personId         which record is affected (null for global issues)
 * @param description      concise description of the problem
 * @param expectedBehavior what should have happened per the specification
 */
public record ReviewIssue(
        @JsonProperty("ruleId")           String ruleId,
        @JsonProperty("severity")         String severity,
        @JsonProperty("personId")         Integer personId,
        @JsonProperty("description")      String description,
        @JsonProperty("expectedBehavior") String expectedBehavior
) {}
