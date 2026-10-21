
package com.freemaf.agent.cdp;



/**

 * Hard-coded DeepSeek DOM selectors. Calibration is not used in the CDP path.

 */

public final class DeepSeekSelectors {



    private DeepSeekSelectors() {}



    /** Input field selector for the message textarea. */

    public static final String INPUT = "textarea[name='search']";



    /** Main assistant response container (markdown). */
    public static final String ASSISTANT_MAIN_CONTENT = ".ds-assistant-message-main-content";

    /** Text used by the "Stop generating" button (used to detect ongoing generation). */
    public static final String STOP_GENERATING_TEXT = "Stop generating";

    /** Selectors for assistant message containers (comma-joined for querySelectorAll). */

    public static final String ASSISTANT_MESSAGE =

            "div.ds-markdown, [data-message-author-role='assistant'], [class~='ds-markdown'], [data-chat-flow-kind='assistant-step']";



    /** Broad detector for "chat has any rendered content" — used by open() to detect restored history. */

    public static final String ANY_MESSAGE_OR_CODE =

            "div.ds-markdown, [data-message-author-role], pre, [class~='ds-markdown']";



    /** All textareas - diagnostic only. */

    public static final String ALL_TEXTAREA = "textarea";



    /** Streaming indicator: presence means generation is in progress. */

    public static final String STREAMING_INDICATOR = "[data-streaming]";


    /** File input element used by DOM.setFileInputFiles (may be hidden). */
    public static final String FILE_INPUT = "input[type='file']";

    /** Attach button (fallback path when FILE_INPUT is not yet in DOM). */
    public static final String ATTACH_BUTTON = "button[aria-label*='attach'], button[aria-label*='\u0444\u0430\u0439\u043b'], button[aria-label*='Attach'], [data-testid='attach-button']";

    /** Attachment node that appears after a successful upload (provisional). */
    public static final String ATTACHMENT_NODE = "[data-file-name], [class*='attachment'], [class*='file-item'], [class*='file-card'], [class*='file-preview'], [class*='attach'], [class*='upload'], [data-testid*='file'], [data-testid*='attach'], [class*='image-preview'], [class*='thumbnail']";



    /** Text label of the New chat button (leaf node); used to enforce a fresh chat. */

    public static final String NEW_CHAT_TEXT = "New chat";

/** Text labels for the DeepThink toggle button (multiple locales). */
    public static final String[] DEEP_THINK_TEXTS = {"DeepThink", "Deep Think", "Глубокое мышление", "深度思考"};
    /** Text labels for the Search toggle button (multiple locales). */
    public static final String[] SEARCH_TEXTS = {"Search", "Умный поиск", "智能搜索", "联网搜索"};
}
