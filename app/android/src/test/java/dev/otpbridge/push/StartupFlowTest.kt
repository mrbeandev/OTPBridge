package dev.otpbridge.push
import org.junit.Assert.*
import org.junit.Test
class StartupFlowTest {
 @Test fun permissionWaitsForSuccessfulSessionUnlock(){val f=StartupFlow();f.beginVisit();assertFalse(f.takePermission(true,false,true,true));assertTrue(f.takePermission(true,true,true,true));assertFalse(f.takePermission(true,true,true,true))}
 @Test fun noSessionOrEmptyVaultCanPromptImmediately(){assertTrue(StartupFlow().takePermission(false,false,true,true));assertTrue(StartupFlow().takePermission(true,false,false,true))}
 @Test fun singleRequestWaitsForUnlockAndNeverReopensAfterBack(){val f=StartupFlow();f.beginVisit();assertFalse(f.takeSingleRequest(true,false,true,1,true));assertTrue(f.takeSingleRequest(true,true,true,1,true));assertFalse(f.takeSingleRequest(true,true,true,1,true));f.beginVisit();assertTrue(f.takeSingleRequest(true,true,true,1,true))}
 @Test fun multipleRequestsStayOnHomeAndNeverPickArbitraryRequest(){val f=StartupFlow();f.beginVisit();assertFalse(f.takeSingleRequest(false,false,true,2,true));assertFalse(f.takeSingleRequest(false,false,true,1,true))}
 @Test fun refreshMayArriveBeforeOrAfterUnlock(){val f=StartupFlow();assertFalse(f.takeSingleRequest(true,true,true,0,true));assertFalse(f.takeSingleRequest(true,true,true,1,false));assertTrue(f.takeSingleRequest(true,true,true,1,true))}
}
