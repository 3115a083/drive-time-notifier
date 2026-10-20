package de.drivetime.notifier.calendar
import de.drivetime.notifier.model.CalendarEventRef
import org.junit.Assert.*
import org.junit.Test
class CalendarConflictsTest {
    private fun event(id:Long,start:Long,end:Long,location:String="")=CalendarEventRef(id,1,"",location,start,end)
    @Test fun overlappingPreviousAppointmentIsNotDiscarded(){
        val target=event(1,1000,1100,"Destination")
        assertEquals(1050L,CalendarConflicts.previousEnd(listOf(target,event(2,900,1050)),target))
    }
    @Test fun appointmentWithoutAddressStillBlocksTravel(){
        val target=event(1,1000,1100,"Destination")
        assertEquals(950L,CalendarConflicts.previousEnd(listOf(target,event(2,900,950)),target))
    }
    @Test fun excludesTargetOccurrenceButNotOtherOverlappingOccurrence(){
        val target=event(1,1000,1100)
        assertEquals(1200L,CalendarConflicts.previousEnd(listOf(target,event(1,900,1200)),target))
        assertNull(CalendarConflicts.previousEnd(listOf(target,event(2,1300,1400)),target))
    }
}
