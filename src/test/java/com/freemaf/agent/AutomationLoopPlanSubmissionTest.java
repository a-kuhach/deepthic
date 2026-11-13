
package com.freemaf.agent;



import org.junit.jupiter.api.Test;



import static org.junit.jupiter.api.Assertions.assertFalse;

import static org.junit.jupiter.api.Assertions.assertTrue;



class AutomationLoopPlanSubmissionTest {



    @Test

    void nullIsNotSubmission() {

        assertFalse(AutomationLoop.planSubmitted(null));

    }



    @Test

    void emptyIsNotSubmission() {

        assertFalse(AutomationLoop.planSubmitted(""));

    }



    @Test

    void legacyPlanSavedTokenIsSubmission() {

        assertTrue(AutomationLoop.planSubmitted("План сохранён"));

    }



    @Test

    void legacyPlanSavedNoYoTokenIsSubmission() {

        assertTrue(AutomationLoop.planSubmitted("План сохранен"));

    }



    @Test

    void readyForPlanReviewIsSubmission() {

        assertTrue(AutomationLoop.planSubmitted("READY_FOR_PLAN_REVIEW"));

    }



    @Test

    void arbitraryTextIsNotSubmission() {

        assertFalse(AutomationLoop.planSubmitted("some random status"));

    }



    @Test

    void mixedWithOtherTokenStillDetectsSubmission() {

        assertTrue(AutomationLoop.planSubmitted("some text | READY_FOR_PLAN_REVIEW | extra"));

    }

}
