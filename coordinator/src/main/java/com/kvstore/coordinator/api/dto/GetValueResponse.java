package com.kvstore.coordinator.api.dto;

/**
 * REST response body for GET operations.
 *
 * <p>JSON example (key found):
 * <pre>
 * {
 *   "found": true,
 *   "value": "Shubh Sahu",
 *   "version": 42,
 *   "timestampMs": 1722935800000,
 *   "routedTo": "node-2"
 * }
 * </pre>
 *
 * <p>JSON example (key not found):
 * <pre>
 * {
 *   "found": false,
 *   "routedTo": "node-2"
 * }
 * </pre>
 */
public record GetValueResponse(
        boolean found,
        String  value,
        long    version,
        long    timestampMs,
        /** ID of the node that actually served this request — useful for debugging routing. */
        String  routedTo
) {
    public static GetValueResponse notFound(String nodeId) {
        return new GetValueResponse(false, null, 0L, 0L, nodeId);
    }

    public static GetValueResponse found(String value, long version, long timestampMs, String nodeId) {
        return new GetValueResponse(true, value, version, timestampMs, nodeId);
    }
}
