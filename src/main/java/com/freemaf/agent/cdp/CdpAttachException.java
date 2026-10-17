
package com.freemaf.agent.cdp;

/**
 * Runtime exception for CDP file-attach failures.
 *
 * Stage identifies where the failure occurred:
 *   validate     - path validation
 *   state        - session/tab state precondition
 *   query-input  - DOM.getDocument / DOM.querySelector
 *   click-attach - click on attach button
 *   chooser      - Page.setInterceptFileChooserDialog / Page.fileChooserOpened
 *   set-files    - DOM.setFileInputFiles
 *   wait-upload  - attachment did not appear in DOM
 */
public final class CdpAttachException extends RuntimeException {

    private final String stage;
    private final String detail;

    public CdpAttachException(String stage, String detail, Throwable cause) {
        super("CDP attach [" + stage + "]: " + detail, cause);
        this.stage = stage;
        this.detail = detail;
    }

    public String getStage() { return stage; }
    public String getDetail() { return detail; }
}