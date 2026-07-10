package org.jawata.mcp.models;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.ArrayList;
import java.util.List;

/**
 * Metadata for tool responses.
 * Includes pagination info, truncation status, and suggestions for AI.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResponseMeta {

    private Integer totalCount;
    private Integer returnedCount;
    private Boolean truncated;
    private List<String> suggestedNextTools;
    private String verbosity;
    /**
     * Sprint 22 (POST layer): a short directional nudge to the next grounded
     * step, injected centrally on every successful tool result (see
     * {@code ToolRegistry.callTool}). Distinct from {@code suggestedNextTools}
     * (tool-specific next lookups): steering names the do → change → verify
     * direction so the result the agent reads keeps it inside JAWATA rather than
     * defaulting to grep / a hand-edit.
     */
    private String steering;

    private ResponseMeta() {
        // Use builder
    }

    public Integer getTotalCount() {
        return totalCount;
    }

    public Integer getReturnedCount() {
        return returnedCount;
    }

    public Boolean getTruncated() {
        return truncated;
    }

    public List<String> getSuggestedNextTools() {
        return suggestedNextTools;
    }

    public String getVerbosity() {
        return verbosity;
    }

    public String getSteering() {
        return steering;
    }

    /** Package-private: central steering injection onto an already-built meta. */
    void setSteering(String steering) {
        this.steering = steering;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private final ResponseMeta meta = new ResponseMeta();

        public Builder totalCount(Integer totalCount) {
            meta.totalCount = totalCount;
            return this;
        }

        public Builder returnedCount(Integer returnedCount) {
            meta.returnedCount = returnedCount;
            return this;
        }

        public Builder truncated(Boolean truncated) {
            meta.truncated = truncated;
            return this;
        }

        public Builder suggestedNextTools(List<String> suggestedNextTools) {
            meta.suggestedNextTools = suggestedNextTools;
            return this;
        }

        public Builder addSuggestedNextTool(String tool) {
            if (meta.suggestedNextTools == null) {
                meta.suggestedNextTools = new ArrayList<>();
            }
            meta.suggestedNextTools.add(tool);
            return this;
        }

        public Builder verbosity(String verbosity) {
            meta.verbosity = verbosity;
            return this;
        }

        public Builder steering(String steering) {
            meta.steering = steering;
            return this;
        }

        public ResponseMeta build() {
            return meta;
        }
    }
}
