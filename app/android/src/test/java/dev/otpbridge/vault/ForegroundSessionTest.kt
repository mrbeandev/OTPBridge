package dev.otpbridge.vault

import org.junit.Assert.*
import org.junit.Test

class ForegroundSessionTest {
 @Test fun copiesAuthorizedKeyAndLocksWithoutPersisting() {
  val key=ByteArray(32){7};val session=ForegroundSession();session.unlock(key);key.fill(0)
  assertTrue(session.unlocked);session.use { assertEquals(7,it[0].toInt()) }
  var used:ByteArray?=null;session.use { used=it };assertTrue(used!!.all{it==0.toByte()})
  session.lock();assertFalse(session.unlocked);assertThrows(IllegalStateException::class.java){session.use{}}
 }
 @Test fun locksAndWipesTemporaryCopyEvenWhenActionThrows(){val s=ForegroundSession();s.unlock(ByteArray(32){1});var copy:ByteArray?=null;assertThrows(IllegalArgumentException::class.java){s.use{copy=it;throw IllegalArgumentException()}};assertTrue(copy!!.all{it==0.toByte()});s.lock()}
}
