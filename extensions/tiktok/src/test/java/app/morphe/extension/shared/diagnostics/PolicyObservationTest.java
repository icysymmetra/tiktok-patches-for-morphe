package app.morphe.extension.shared.diagnostics;

import static org.junit.Assert.*;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(manifest=Config.NONE,sdk=30)
public class PolicyObservationTest {
    @Before public void before(){RecoveryCapture.resetForTests();}
    @After public void after(){RecoveryCapture.resetForTests();}
    @Test public void defaultsObserveWithoutEnablingAnIntervention(){
        assertFalse(PolicyObservation.bypassEnabled());
        assertTrue(PolicyObservation.state().startsWith("policy_mode=observe reference_requests=0,0 young_requests=0"));
        assertFalse(PolicyObservation.youngRequest(.25));
        assertTrue(PolicyObservation.state().contains("young_request_hex=3fd0_0000_0000_0000"));
    }
    @Test public void nativeResultsStayPairedWithTheFirstCallUnderConcurrency()throws Exception{
        int token=PolicyObservation.enterConfig(35,11,22);
        java.util.concurrent.atomic.AtomicReference<Throwable> failure=new java.util.concurrent.atomic.AtomicReference<>();
        Thread[] workers=new Thread[8];
        for(int i=0;i<workers.length;i++){
            workers[i]=new Thread(()->{try{int other=PolicyObservation.enterConfig(35,33,44);assertEquals(0,other);}catch(Throwable e){failure.set(e);}});workers[i].start();
        }
        for(Thread t:workers){t.join(5000);assertFalse(t.isAlive());}
        assertNull(failure.get());
        PolicyObservation.returned(token,7);
        String state=PolicyObservation.state();
        assertTrue(state,state.contains("gc_config_calls=multiple bypassed=0 sdk=35 clear_hex=0000_0000_0000_000b type_hex=0000_0000_0000_0016 phase=returned result_hex=0000_0000_0000_0007"));
    }
    @Test public void bypassIsNotMisreportedAsANativeReturn(){
        int token=PolicyObservation.record(1,35,12,24,1,0,true);
        PolicyObservation.returned(token,1);
        String state=PolicyObservation.state();assertTrue(state.contains("phase=bypassed"));assertFalse(state.contains("result_hex="));
    }
    @Test public void metadataAndReceiptChangesAreBoundedAndSurviveRedaction(){
        PolicyObservation.youngRequest(Double.MAX_VALUE);
        for(int i=0;i<4;i++)PolicyObservation.returned(PolicyObservation.record(i,35,Long.MIN_VALUE,Long.MAX_VALUE,1,1,false),Integer.MIN_VALUE);
        for(int i=0;i<4;i++)PolicyObservation.record(i,35,0,0,0,0,false);
        PolicyObservation.youngRequest(1);
        String bounded=PolicyObservation.state();
        for(int j=0;j<1000;j++){for(int i=0;i<4;i++)PolicyObservation.record(i,35,j,j,0,0,false);PolicyObservation.youngRequest(j);}
        assertEquals(bounded,PolicyObservation.state());
        assertEquals(bounded,DiagnosticRedactor.redact(bounded));
        assertTrue(bounded.length()+"length="+bounded.length(),bounded.length()<=RecoveryTimeline.MAX_POLICY);
    }
    @Test public void oversizedStatusKeepsItsPriorObservationWithinBothStoreBudgets(){
        String suffix="\n[PRIOR SESSION OBSERVATION RELATIVE TO STATUS TIME]\npolicy_latest: "+PolicyObservation.state()+"\ncontrol_latest: resumed_s=1200\n";
        String report="MORPHE DIAGNOSTIC REPORT\nsource: recovery_trial_status\n[TRIAL BUILD]\n"+"\u2603/".repeat(5000)+suffix;
        String fitted=RecoveryCapture.fitControlActivation(report);
        assertTrue(fitted.endsWith(suffix));assertTrue(fitted.length()<=6000);
        assertTrue(RecoveryCapture.storedBytes(fitted)<=RecoveryCapture.MAX_STORED_ACTIVATION);
    }
}
