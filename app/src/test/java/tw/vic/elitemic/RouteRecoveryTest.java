package tw.vic.elitemic;

import org.junit.Test;
import static org.junit.Assert.assertEquals;
import static tw.vic.elitemic.RoutePolicy.Action.*;

public class RouteRecoveryTest {
    @Test public void lostAfterStableRouteCanRecoverWithoutIntermediateCallbacks() {
        RoutePolicy policy=new RoutePolicy();
        assertEquals(START, policy.decide(false,41,false,false,0));
        assertEquals(START, policy.decide(false,41,false,false,8100));
        assertEquals(ALREADY_ROUTED, policy.decide(false,41,true,true,8500));
        // No device/mode callback arrives during a minute of normal use.
        assertEquals(START, policy.decide(false,41,true,false,68500));
    }
    @Test public void flickeringConfirmationDoesNotContinuouslyRefillRetryBudget() {
        RoutePolicy policy=new RoutePolicy();
        assertEquals(START, policy.decide(false,41,false,false,0));
        assertEquals(ALREADY_ROUTED, policy.decide(false,41,true,true,1000));
        assertEquals(START, policy.decide(false,41,true,false,8100));
        assertEquals(ALREADY_ROUTED, policy.decide(false,41,true,true,8900));
        assertEquals(FAILED, policy.decide(false,41,true,false,17000));
        assertEquals(FAILED, policy.decide(false,41,false,false,67000));
    }
}
