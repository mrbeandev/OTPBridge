package dev.otpbridge.totp
import org.junit.Assert.*
import org.junit.Test
class CodeTimingTest {
 @Test fun countsDownAndRollsAtExactPeriodBoundary(){assertEquals(0L,CodeTiming.validFrom(29999,30));assertEquals(30000L,CodeTiming.validUntil(29999,30));assertEquals(1,CodeTiming.secondsRemaining(29999,30000));assertEquals(0,CodeTiming.secondsRemaining(30000,30000));assertEquals(60000L,CodeTiming.validUntil(30000,30));assertEquals(30,CodeTiming.secondsRemaining(30000,60000))}
}
