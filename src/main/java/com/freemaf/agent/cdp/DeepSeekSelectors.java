
package com.freemaf.agent.cdp;

/**
 * Hard-coded DeepSeek DOM selectors. Calibration is not used in the CDP path.
 */
public final class DeepSeekSelectors {

    private DeepSeekSelectors() {}

    /** Input field selector for the message textarea. */
    public static final String INPUT = "textarea[name='search']";

    /** Selectors for assistant message containers (comma-joined for querySelectorAll). */
    public static final String ASSISTANT_MESSAGE =
            "div.ds-markdown, [data-message-author-role='assistant']";

    /** All textareas - diagnostic only. */
    public static final String ALL_TEXTAREA = "textarea";

    /** Streaming indicator: presence means generation is in progress. */
    public static final String STREAMING_INDICATOR = "[data-streaming]";
}
