package dev.otpbridge.protocol
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
class RequestContextTest {
 @Test fun permitsBoundedContextAndKeepsLocationAccuracy(){val c=JSONObject("""{"device":{"name":"Laptop","hardwareConcurrency":8},"location":{"latitude":12.3,"longitude":77.2,"accuracyMeters":45,"capturedAt":1800000000000}}""");RequestContext.validate(c);assertTrue(RequestContext.lines(c).any{it.contains("±45 m")})}
 @Test fun rejectsUnknownFieldsAndInvalidCoordinates(){assertThrows(IllegalArgumentException::class.java){RequestContext.validate(JSONObject("""{"serialNumber":"private"}"""))};assertThrows(IllegalArgumentException::class.java){RequestContext.validate(JSONObject("""{"location":{"latitude":999,"longitude":0,"accuracyMeters":1,"capturedAt":1}}"""))}}
 @Test fun rejectsCoercedNumbers(){assertThrows(IllegalArgumentException::class.java){RequestContext.validate(JSONObject("""{"location":{"latitude":"12","longitude":0,"accuracyMeters":1,"capturedAt":1}}"""))}}
}
