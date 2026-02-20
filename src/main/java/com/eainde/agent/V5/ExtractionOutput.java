package com.eainde.agent.V5;

package com.db.clm.kyc.ai.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;

/**
 * The final output of the CSM extraction pipeline.
 *
 * <p>Contains exactly one array of {@link ExtractedRecord} objects,
 * conforming to the JSON schema defined in Section J1:
 * {@code {"extracted_records": [...]}}</p>
 *
 * <p>This is the type returned by the output-assembler (Phase 5) and
 * the output-refiner (Phase 7, after critic corrections).</p>
 *
 * @param extractedRecords the complete list of extracted person records
 */
public record ExtractionOutput(
        @JsonProperty("extracted_records") List<ExtractedRecord> extractedRecords
) {

    /**
     * Creates an empty extraction output (used when no candidates are found per J6).
     */
    public static ExtractionOutput empty() {
        return new ExtractionOutput(List.of());
    }

    /**
     * @return the number of records in the output
     */
    public int size() {
        return extractedRecords != null ? extractedRecords.size() : 0;
    }

    /**
     * @return the number of records where isCsm is true
     */
    public long csmCount() {
        return extractedRecords != null
                ? extractedRecords.stream().filter(ExtractedRecord::isCsm).count()
                : 0;
    }
}
