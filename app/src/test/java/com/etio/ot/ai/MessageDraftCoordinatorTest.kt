package com.etio.ot.ai

import com.etio.ot.core.Clock
import com.etio.ot.data.FakeStore
import com.etio.ot.data.config.SeedCase
import com.etio.ot.data.config.SeedConfig
import com.etio.ot.data.config.SeedSource
import com.etio.ot.data.local.entity.DelayRecordEntity
import com.etio.ot.data.model.Audience
import com.etio.ot.data.model.Avoidability
import com.etio.ot.data.model.DelayCode
import com.etio.ot.data.repository.CaseRepository
import com.etio.ot.data.repository.DelayRepository
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Job 2 running behind the coordinator's back, so Notify is a reveal rather than a
 * wait. The property that matters most is that two triggers cannot overlap — the
 * repository clears previous rows before it writes, so two concurrent runs would
 * delete each other's work.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class MessageDraftCoordinatorTest {

    private val t0 = 1_700_000_000_000L

    private fun record(id: String = "d1") = DelayRecordEntity(
        id = id,
        caseId = "c1",
        createdAtMs = t0,
        transcriptRaw = "the set came back wet",
        code = DelayCode.STERILE_SET_UNAVAILABLE,
        attributedDept = "CSSD",
        avoidable = Avoidability.AVOIDABLE,
        estimatedMin = 40,
        note = "Set came back wet",
    )

    private class Rig(scope: TestScope) {
        val store = FakeStore()
        val engine = RecordingLlmEngine()
        val delays = DelayRepository(
            delayDao = store.delays,
            messageDao = store.messages,
            classifier = DelayClassifier(engine, fakePromptSource()),
            drafter = MessageDrafter(engine, fakePromptSource()),
            caseRepository = CaseRepository(
                store.cases,
                store.events,
                SeedSource { SeedConfig(listOf(SeedCase("1", "OT-2", "P", "Dr A", "09:00", 60))) },
                Clock { 1_700_000_000_000L },
            ),
            clock = Clock { 1_700_000_000_000L },
        )
        val coordinator = MessageDraftCoordinator(delays, scope)
    }

    @Test
    fun `starting drafts one message per audience`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size) { rig.engine.queueSuccess("body") }

        rig.coordinator.start(record())
        runCurrent()

        assertEquals(Audience.entries.size, rig.store.messages.rows.value.size)
        assertEquals(
            Audience.entries.toSet(),
            rig.store.messages.rows.value.map { it.audience }.toSet(),
        )
    }

    @Test
    fun `pending empties out as each audience lands`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size) { rig.engine.queueSuccess("body") }

        rig.coordinator.start(record())
        assertEquals(
            "everything is outstanding the instant it starts",
            Audience.entries.size,
            rig.coordinator.pending.value["d1"]?.size,
        )

        runCurrent()
        assertTrue(
            "the record drops out of the map when it finishes",
            rig.coordinator.pending.value["d1"].isNullOrEmpty(),
        )
    }

    @Test
    fun `a second start while the first is running is ignored`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size) { rig.engine.queueSuccess("body") }

        rig.coordinator.start(record())
        // Deliberately no runCurrent: the first run is still in flight.
        rig.coordinator.start(record())
        runCurrent()

        assertEquals(
            "a duplicate run would have needed a second set of responses",
            Audience.entries.size,
            rig.engine.calls.size,
        )
        assertEquals(Audience.entries.size, rig.store.messages.rows.value.size)
    }

    @Test
    fun `isRunning is true only while a record is in flight`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size) { rig.engine.queueSuccess("body") }

        assertFalse(rig.coordinator.isRunning("d1"))
        rig.coordinator.start(record())
        assertTrue(rig.coordinator.isRunning("d1"))

        runCurrent()
        assertFalse(rig.coordinator.isRunning("d1"))
    }

    @Test
    fun `two different records draft independently`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size * 2) { rig.engine.queueSuccess("body") }

        rig.coordinator.start(record("d1"))
        rig.coordinator.start(record("d2"))
        runCurrent()

        assertEquals(Audience.entries.size, rig.store.messages.rows.value.count { it.delayRecordId == "d1" })
        assertEquals(Audience.entries.size, rig.store.messages.rows.value.count { it.delayRecordId == "d2" })
    }

    @Test
    fun `an inference failure still leaves a message, and the run still ends`() =
        runTest(StandardTestDispatcher()) {
            val rig = Rig(this)
            repeat(Audience.entries.size) { rig.engine.queueFailure() }

            rig.coordinator.start(record())
            runCurrent()

            assertEquals(
                "every audience falls back to the deterministic body",
                Audience.entries.size,
                rig.store.messages.rows.value.size,
            )
            assertFalse("the coordinator must not stay stuck", rig.coordinator.isRunning("d1"))
            assertTrue(rig.store.messages.rows.value.all { it.body.isNotBlank() })
        }

    @Test
    fun `pendingFor reports only the record asked about`() = runTest(StandardTestDispatcher()) {
        val rig = Rig(this)
        repeat(Audience.entries.size) { rig.engine.queueSuccess("body") }

        rig.coordinator.start(record("d1"))

        assertEquals(Audience.entries.size, rig.coordinator.pending.value["d1"]?.size)
        assertTrue(rig.coordinator.pending.value["d2"].isNullOrEmpty())
        runCurrent()
    }
}
