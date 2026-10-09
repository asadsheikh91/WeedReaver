package com.example.andriodfypprototype

import com.example.andriodfypprototype.data.HracGroup
import com.example.andriodfypprototype.data.Iso
import com.example.andriodfypprototype.data.Pt
import com.example.andriodfypprototype.data.Severity
import com.example.andriodfypprototype.data.SurveyRole
import com.example.andriodfypprototype.data.SurveyStatus
import com.example.andriodfypprototype.data.WeedClass
import com.example.andriodfypprototype.data.ZoneState
import com.example.andriodfypprototype.data.net.StationDto
import com.example.andriodfypprototype.data.net.ZoneDto
import com.example.andriodfypprototype.data.net.SyncPullOut
import com.example.andriodfypprototype.data.net.WrJson
import com.example.andriodfypprototype.data.toDomain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * The phone's side of the wire contract. `sync-pull-full.json` is a real `GET /sync/pull` response
 * from the backend's demonstration seed (as operator OP-0147 on handset D-01). WrJson ignores unknown
 * keys and coerces bad values to defaults, so a renamed field would not crash the app, it would
 * silently read a default: every assertion here therefore checks a real value, not just that it parsed.
 */
class SyncContractTest {

    private val pull: SyncPullOut = WrJson.decodeFromString(
        SyncPullOut.serializer(),
        javaClass.classLoader!!.getResource("sync-pull-full.json")!!.readText()
    )

    @Test
    fun `settings carry the published threshold`() {
        assertTrue(pull.full)
        assertEquals(10f, pull.settings.thresholdPct)
        assertEquals("Pindi Bhattian field station", pull.settings.orgName)
        assertEquals("Rabi 2026-27", pull.settings.seasonLabel)
    }

    @Test
    fun `fields map to parcels whose area is computed from the boundary`() {
        val fields = pull.fields.map { it.toDomain() }.associateBy { it.id }
        assertEquals(setOf("F-047", "F-112", "F-203"), fields.keys)
        val chak = fields.getValue("F-047")
        assertEquals("8.45", "%.2f".format(chak.areaAcres))
        assertEquals(Pt(1f, 84f), chak.gate)
        assertEquals(4711, chak.landscape.seed)
        assertEquals(0.16f, chak.landscape.mustardBias)
    }

    @Test
    fun `zones keep their code, severity, area and observed state`() {
        val zones = pull.zones.filter { it.fieldId == "F-047" }.map { it.toDomain() }.associateBy { it.id }
        assertEquals(setOf("Z-A", "Z-B", "Z-C", "Z-D", "Z-E"), zones.keys)
        val a = zones.getValue("Z-A")
        assertEquals(Severity.HEAVY, a.severity)
        assertEquals(988, a.areaSqm)
        assertEquals(ZoneState.FLAGGED, a.state)
        assertEquals(60f, a.cx)
        assertEquals(WeedClass.BROADLEAF, zones.getValue("Z-C").dominantClass) // GRASS is the fallback
    }

    @Test
    fun `an observed zone state and a non-default threshold survive decoding`() {
        // The seed has only FLAGGED zones and a 10% threshold, which are also the defaults.
        val zone = WrJson.decodeFromString(ZoneDto.serializer(), pull.zones.first().let { z ->
            WrJson.encodeToString(ZoneDto.serializer(), z).replace("\"FLAGGED\"", "\"TREATED\"")
        }).toDomain()
        assertEquals(ZoneState.TREATED, zone.state)
        val station = WrJson.decodeFromString(StationDto.serializer(), """{"thresholdPct": 12.5, "serverTime": "2027-01-22T05:00:00Z"}""")
        assertEquals(12.5f, station.thresholdPct)
    }

    @Test
    fun `surveys keep role, status and the station-local flight time`() {
        val s01 = pull.surveys.first { it.id == "S-01" }.toDomain()
        assertEquals(SurveyRole.PRE, s01.role)
        assertEquals(SurveyStatus.READY, s01.status)
        assertEquals(Instant.parse("2027-01-07T04:12:00Z").toEpochMilli(), s01.flownAt) // 09:12 in Pakistan
        assertEquals(612, s01.images)
        // PRE is also the mapper's fallback, so check a role that is not.
        assertEquals(SurveyRole.PLUS_14D, pull.surveys.first { it.id == "S-02" }.toDomain().role)
    }

    @Test
    fun `scans keep confidence, abstention and the runner-up candidates`() {
        val scans = pull.scans.associateBy { it.id }
        assertEquals(6, scans.size)
        val abstained = scans.getValue("SC-036").toDomain(null, null, local = false)
        assertTrue(abstained.abstained)
        assertEquals(0.61f, abstained.confidence)
        assertEquals("Phalaris minor" to 0.34f, abstained.runnerUp.first())
        assertEquals("Zone B", abstained.zoneLabel)
    }

    @Test
    fun `treatments keep the dose exactly as written`() {
        val t = pull.treatments.first { it.id == "T-2025-A" }.toDomain(local = false)
        assertEquals(HracGroup.G1, t.hracGroup)
        assertEquals("100", t.doseRecorded)
        assertEquals("g / acre", t.doseUnit)
        assertEquals(listOf("Zone A", "Zone B", "Zone E"), t.zoneLabels)
        assertEquals(Instant.parse("2025-12-12T05:30:00Z").toEpochMilli(), t.appliedAt)
        assertEquals(HracGroup.G2, pull.treatments.first { it.id == "T-2025-C" }.toDomain(local = false).hracGroup) // G1 is the fallback
    }

    @Test
    fun `reference lists arrive complete`() {
        assertEquals(11, pull.products.size)
        assertEquals(HracGroup.G1, pull.products.first { it.trade == "Topik 15 WP" }.toDomain().hrac)
        assertEquals(5, pull.species.size)
        assertEquals(48, pull.quadrats.first { it.id == "Q-01" }.toDomain().speciesCounts.sumOf { it.second })
    }

    @Test
    fun `Iso reads UTC instants, offsets and date-only strings`() {
        assertEquals(1800000000000L, Iso.ms("2027-01-15T08:00:00Z"))
        assertEquals(Iso.ms("2027-01-15T08:00:00Z"), Iso.ms("2027-01-15T13:00:00+05:00"))
        assertEquals("2026-11-12", Iso.date(Iso.ms("2026-11-12")!!))
        assertEquals(null, Iso.ms("not a date"))
    }
}
