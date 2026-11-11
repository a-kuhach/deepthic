
package com.freemaf.agent;



import org.junit.jupiter.api.Test;

import com.freemaf.agent.StatusMarker.State;

import com.freemaf.agent.MainFrame.SubmitRoute;

import static org.junit.jupiter.api.Assertions.assertEquals;



class SubmitRouteTest {



    @Test

    void stoppedRoutesToStartRegardlessOfLoop() {

        assertEquals(SubmitRoute.START, MainFrame.computeSubmitRoute(State.STOPPED, false));

        assertEquals(SubmitRoute.START, MainFrame.computeSubmitRoute(State.STOPPED, true));

    }



    @Test

    void pausedWithLoopRoutesToResume() {

        assertEquals(SubmitRoute.RESUME, MainFrame.computeSubmitRoute(State.PAUSED, true));

    }



    @Test

    void pausedWithoutLoopFallsThroughToQueueOnly() {

        assertEquals(SubmitRoute.QUEUE_ONLY, MainFrame.computeSubmitRoute(State.PAUSED, false));

    }



    @Test

    void workingWithLoopRoutesToPending() {

        assertEquals(SubmitRoute.PENDING, MainFrame.computeSubmitRoute(State.WORKING, true));

    }



    @Test

    void workingWithoutLoopFallsThroughToQueueOnly() {

        assertEquals(SubmitRoute.QUEUE_ONLY, MainFrame.computeSubmitRoute(State.WORKING, false));

    }



    @Test

    void planningFallsThroughToQueueOnly() {

        assertEquals(SubmitRoute.QUEUE_ONLY, MainFrame.computeSubmitRoute(State.PLANNING, true));

    }



    @Test

    void calibrationFallsThroughToQueueOnly() {

        assertEquals(SubmitRoute.QUEUE_ONLY, MainFrame.computeSubmitRoute(State.CALIBRATION, true));

    }

}
