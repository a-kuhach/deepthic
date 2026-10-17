
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

    /** File input element used by DOM.setFileInputFiles (may be hidden). */
    public static final String FILE_INPUT = "input[type='file']";

    /** Attach button (fallback path when FILE_INPUT is not yet in DOM). */
    public static final String ATTACH_BUTTON = "button[aria-label*='attach'], button[aria-label*='\u0444\u0430\u0439\u043b'], button[aria-label*='Attach'], [data-testid='attach-button']";

    /** Attachment node that appears after a successful upload (provisional). */
    public static final String ATTACHMENT_NODE = "[data-file-name], [class*='attachment'], [class*='file-item']";


    /** Text label of the New chat button (leaf node); used to enforce a fresh chat. */
    public static final String NEW_CHAT_TEXT = "New chat";}
