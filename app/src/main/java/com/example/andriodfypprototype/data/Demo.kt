package com.example.andriodfypprototype.data

import java.util.Calendar

/**
 * Seed content. Sites and species follow spec sections 3.5, 5.1 and 41.1.
 *
 * Parcels follow the canal-colony killa grid (one killa ≈ 60 × 67 m ≈ one acre), which is
 * why Punjab fields are long rectangles rather than blobs. Every area shown in the app is
 * computed from these vertices; nothing is typed in.
 */
object Demo {

    const val SEASON = "Rabi 2026-27"
    const val OPERATOR = "Asad Mehmood"
    const val OPERATOR_ID = "OP-0147"
    const val OPERATOR_ROLE = "Field operator"
    const val ORG = "Pindi Bhattian field station"
    const val MODEL_AERIAL = "wr-seg-deeplabv3p-r50 v0.4.1"
    const val MODEL_LEAF = "wr-leaf-yolo11n-int8 v0.3.2"
    const val APP_VERSION = "0.9.4 (212)"

    /** Demo date: mid-season, between the pre-treatment flight and the +14 d follow-up. */
    fun date(day: Int, month: Int, year: Int, hour: Int = 9, minute: Int = 0): Long =
        Calendar.getInstance().apply {
            set(year, month - 1, day, hour, minute, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    /**
     * Wall-clock time of day, on the demo date. Minutes advance for real while the app runs.
     * Once the phone has synced it follows the station's clock instead ([setClockOffset]), so
     * "today" matches the server (which may itself be pinned with WR_DEMO_CLOCK_DATE).
     */
    private val demoOffset: Long = run {
        val real = Calendar.getInstance()
        val demo = Calendar.getInstance().apply {
            set(2027, Calendar.JANUARY, 22, real.get(Calendar.HOUR_OF_DAY), real.get(Calendar.MINUTE))
        }
        demo.timeInMillis - real.timeInMillis
    }

    @Volatile private var clockOffset: Long = demoOffset

    fun setClockOffset(serverMinusDevice: Long?) {
        clockOffset = serverMinusDevice ?: demoOffset
    }

    fun now(): Long = System.currentTimeMillis() + clockOffset

    // ------------------------------------------------------------------ parcels

    val fields = listOf(
        FieldParcel(
            id = "F-047", name = "Chak 47", village = "Pindi Bhattian, Hafizabad",
            boundary = listOf(
                Pt(22f, 0f), Pt(243f, 0f), Pt(245f, 142f), Pt(1f, 143f), Pt(0f, 24f), Pt(22f, 24f)
            ),
            lat = 31.89420, lon = 73.27110, capturedBy = OPERATOR,
            landscape = Landscape(
                seed = 4711,
                road = listOf(Pt(-11f, -260f), Pt(-12f, 60f), Pt(-9f, 420f)),
                watercourse = listOf(Pt(-200f, 148f), Pt(120f, 149f), Pt(460f, 151f)),
                farmstead = Pt(-58f, -44f),
                tubewell = Pt(11f, 12f),
                mustardBias = 0.16f
            ),
            gate = Pt(1f, 84f)
        ),
        FieldParcel(
            id = "F-112", name = "Canal side", village = "Pindi Bhattian, Hafizabad",
            boundary = listOf(Pt(0f, 0f), Pt(148f, 0f), Pt(172f, 86f), Pt(2f, 84f)),
            lat = 31.89010, lon = 73.28030, capturedBy = OPERATOR,
            landscape = Landscape(
                seed = 1123,
                canal = listOf(Pt(122f, -260f), Pt(250f, 300f)),
                road = listOf(Pt(-260f, -9f), Pt(60f, -10f), Pt(146f, -11f)),
                watercourse = listOf(Pt(-4f, -200f), Pt(-5f, 300f)),
                mustardBias = 0.08f
            ),
            gate = Pt(64f, 1f)
        ),
        FieldParcel(
            id = "F-203", name = "North plot", village = "Jalalpur Bhattian",
            boundary = listOf(Pt(0f, 2f), Pt(182f, 0f), Pt(183f, 88f), Pt(1f, 90f)),
            lat = 31.90340, lon = 73.26500, capturedBy = OPERATOR,
            landscape = Landscape(
                seed = 2031,
                road = listOf(Pt(-300f, -28f), Pt(480f, -24f)),
                village = Pt(70f, -120f),
                watercourse = listOf(Pt(186f, -200f), Pt(188f, 300f)),
                mustardBias = 0.2f
            ),
            gate = Pt(92f, 1f)
        )
    )

    /** The parcel a boundary walk traces. The imagery shows it before any line is drawn. */
    val walkParcel = listOf(Pt(0f, 0f), Pt(128f, 1f), Pt(130f, 104f), Pt(1f, 106f))
    val walkLandscape = Landscape(
        seed = 5150,
        road = listOf(Pt(-14f, -200f), Pt(-13f, 320f)),
        watercourse = listOf(Pt(-200f, 110f), Pt(400f, 111f)),
        farmstead = Pt(170f, -30f)
    )

    /** Starting view for drawing a boundary on the map. */
    val drawParcel = listOf(Pt(0f, 0f), Pt(121f, 0f), Pt(122f, 134f), Pt(0f, 134f))
    val drawLandscape = Landscape(
        seed = 6203,
        road = listOf(Pt(-300f, -12f), Pt(420f, -10f)),
        watercourse = listOf(Pt(126f, -200f), Pt(127f, 340f)),
        mustardBias = 0.18f
    )

    val seasons = listOf(
        FieldSeason("FS-047", "F-047", "Wheat", SEASON, date(12, 11, 2026), 22, "Akbar-2019", null),
        FieldSeason("FS-112", "F-112", "Wheat", SEASON, date(15, 11, 2026), 22, "Dilkash-2020", null),
        FieldSeason("FS-203", "F-203", "Wheat", SEASON, date(18, 11, 2026), 20, "Galaxy-2013", null)
    )

    val surveys = listOf(
        Survey("S-01", "FS-047", date(7, 1, 2027, 9, 12), SurveyRole.PRE, 15, "DJI Mavic 3M · 20 MP RGB", 0.42f, SurveyStatus.READY, 612),
        Survey("S-02", "FS-047", date(21, 1, 2027, 8, 50), SurveyRole.PLUS_14D, 15, "DJI Mavic 3M · 20 MP RGB", 0.43f, SurveyStatus.READY, 598),
        Survey("S-03", "FS-047", date(4, 2, 2027, 9, 0), SurveyRole.PLUS_28D, 15, "DJI Mavic 3M · 20 MP RGB", 0.42f, SurveyStatus.SCHEDULED),
        Survey("S-04", "FS-112", date(8, 1, 2027, 10, 20), SurveyRole.PRE, 15, "DJI Mavic 3M · 20 MP RGB", 0.44f, SurveyStatus.READY, 287),
        Survey("S-06", "FS-112", date(21, 1, 2027, 10, 5), SurveyRole.PLUS_14D, 15, "DJI Mavic 3M · 20 MP RGB", 0.44f, SurveyStatus.PROCESSING, 281, 0.41f),
        Survey("S-05", "FS-203", date(26, 1, 2027, 9, 30), SurveyRole.PRE, 15, "DJI Mavic 3M · 20 MP RGB", 0f, SurveyStatus.SCHEDULED)
    )

    fun fieldFor(fieldId: String, boundary: List<Pt>): InfestationField = when (fieldId) {
        "F-047" -> InfestationField(
            boundary, listOf(
                Patch(60f, 44f, 22f, 0.86f, WeedClass.GRASS, "A", 1.45f),
                Patch(152f, 50f, 17f, 0.62f, WeedClass.GRASS, "B", 1.5f),
                Patch(204f, 106f, 19f, 0.72f, WeedClass.BROADLEAF, "C", 1.1f),
                Patch(100f, 108f, 15f, 0.46f, WeedClass.BROADLEAF, "D", 1.15f),
                Patch(28f, 100f, 11f, 0.36f, WeedClass.GRASS, "E", 1.6f),
                Patch(126f, 14f, 9f, 0.22f, WeedClass.GRASS, "F", 1.8f)
            ), 4711
        )
        "F-112" -> InfestationField(
            boundary, listOf(
                Patch(52f, 46f, 17f, 0.44f, WeedClass.GRASS, "A", 1.5f),
                Patch(124f, 28f, 13f, 0.33f, WeedClass.BROADLEAF, "B", 1.1f),
                Patch(96f, 68f, 10f, 0.19f, WeedClass.GRASS, "C", 1.6f)
            ), 1123
        )
        "F-203" -> InfestationField(
            boundary, listOf(Patch(92f, 46f, 13f, 0.16f, WeedClass.GRASS, "A")), 2031
        )
        else -> InfestationField(boundary, emptyList(), fieldId.hashCode())
    }

    /** +14 d survey: zone A responded, zone C barely moved - the resistance signal. */
    val efficacyFactors = mapOf("A" to 0.18f, "B" to 0.34f, "C" to 0.86f, "D" to 0.26f, "E" to 0.30f)

    val products = listOf(
        Product("Topik 15 WP", "Clodinafop-propargyl", HracGroup.G1, WeedClass.GRASS, "Wheat", "Wettable powder"),
        Product("Puma Super 75 EW", "Fenoxaprop-P-ethyl", HracGroup.G1, WeedClass.GRASS, "Wheat", "Emulsion in water"),
        Product("Axial 50 EC", "Pinoxaden", HracGroup.G1, WeedClass.GRASS, "Wheat", "Emulsifiable concentrate"),
        Product("Leader 75 WG", "Sulfosulfuron", HracGroup.G2, WeedClass.GRASS, "Wheat", "Water-dispersible granule"),
        Product("Atlantis 3.6 WG", "Mesosulfuron + iodosulfuron", HracGroup.G2, WeedClass.GRASS, "Wheat", "Water-dispersible granule"),
        Product("Arelon 50 WP", "Isoproturon", HracGroup.G5, WeedClass.GRASS, "Wheat", "Wettable powder"),
        Product("Sencor 70 WP", "Metribuzin", HracGroup.G5, WeedClass.BROADLEAF, "Wheat", "Wettable powder"),
        Product("Stomp 330 EC", "Pendimethalin", HracGroup.G3, WeedClass.GRASS, "Wheat", "Emulsifiable concentrate"),
        Product("Sakura 85 WG", "Pyroxasulfone", HracGroup.G15, WeedClass.GRASS, "Wheat", "Water-dispersible granule"),
        Product("Buctril Super 60 EC", "Bromoxynil + MCPA", HracGroup.G4, WeedClass.BROADLEAF, "Wheat", "Emulsifiable concentrate"),
        Product("Round-up 41 SL", "Glyphosate", HracGroup.G9, WeedClass.BROADLEAF, "Pre-sow", "Soluble liquid")
    )

    /** Tier 2 close-range label set, spec section 5.1. */
    data class SpeciesEntry(
        val latin: String, val local: String, val common: String, val cls: WeedClass, val note: String
    )

    val species = listOf(
        SpeciesEntry("Phalaris minor", "Dumbi sitti", "Littleseed canarygrass", WeedClass.GRASS, "Mimics wheat at distance. The membranous ligule and absent auricles separate it."),
        SpeciesEntry("Avena ludoviciana", "Jangli jai", "Wild oat", WeedClass.GRASS, "Routinely confused with P. minor. Look for the twisted awn and hairy leaf margin."),
        SpeciesEntry("Chenopodium album", "Bathu", "Lambsquarters", WeedClass.BROADLEAF, "Mealy white coating on young leaves. Visually distinctive."),
        SpeciesEntry("Convolvulus arvensis", "Lehli", "Field bindweed", WeedClass.BROADLEAF, "Arrow-shaped leaves on a twining stem. Climbs the crop."),
        SpeciesEntry("Triticum aestivum", "Kanak", "Wheat (crop)", WeedClass.CROP, "Crop plant. Clasping auricles with hairs. No action.")
    )

    /** Fixed outcome sequence so a demonstration is reproducible, including ABSTAIN cases. */
    data class ScanOutcome(
        val species: SpeciesEntry, val confidence: Float, val abstain: Boolean,
        val frameId: String?, val leafSeed: Int, val runnerUp: List<Pair<String, Float>>, val ms: Int
    )

    val scanOutcomes = listOf(
        ScanOutcome(species[0], 0.91f, false, "ARUCO-12", 11, listOf("Avena ludoviciana" to 0.06f, "Triticum aestivum" to 0.02f), 142),
        ScanOutcome(species[1], 0.61f, true, null, 23, listOf("Phalaris minor" to 0.34f, "Triticum aestivum" to 0.04f), 156),
        ScanOutcome(species[2], 0.95f, false, "ARUCO-07", 31, listOf("Convolvulus arvensis" to 0.03f, "Phalaris minor" to 0.01f), 138),
        ScanOutcome(species[0], 0.78f, false, null, 47, listOf("Triticum aestivum" to 0.14f, "Avena ludoviciana" to 0.06f), 149),
        ScanOutcome(species[3], 0.57f, true, "ARUCO-19", 53, listOf("Chenopodium album" to 0.29f, "Phalaris minor" to 0.09f), 161),
        ScanOutcome(species[4], 0.93f, false, null, 67, listOf("Phalaris minor" to 0.05f, "Avena ludoviciana" to 0.01f), 133)
    )

    val quadrats = listOf(
        QuadratRecord(
            "Q-01", "ARUCO-12", date(7, 1, 2027, 11, 40), "Chak 47", "Zone A",
            listOf("Phalaris minor" to 34, "Avena ludoviciana" to 11, "Chenopodium album" to 3),
            "Dr. S. Anjum, station agronomist", 12
        ),
        QuadratRecord(
            "Q-02", "ARUCO-07", date(7, 1, 2027, 12, 5), "Chak 47", "Zone C",
            listOf("Chenopodium album" to 21, "Convolvulus arvensis" to 8),
            "Dr. S. Anjum, station agronomist", 7
        ),
        QuadratRecord(
            "Q-03", "ARUCO-19", date(21, 1, 2027, 10, 15), "Chak 47", "Zone C",
            listOf("Convolvulus arvensis" to 14, "Phalaris minor" to 12),
            "Dr. S. Anjum, station agronomist", 19
        )
    )

    /** Prior-season applications; drives the rotation warning of spec section 41.2. */
    val priorTreatments = listOf(
        TreatmentRecord(
            "T-2025-A", "FS-047-prev", "F-047", "Chak 47", listOf("Zone A", "Zone B", "Zone E"),
            date(12, 12, 2025, 10, 30), "Topik 15 WP", "Clodinafop-propargyl", HracGroup.G1,
            "100", "g / acre", "Knapsack", "Tillering (GS 21-25)", OPERATOR, 2.4f, true, "100"
        ),
        TreatmentRecord(
            "T-2024-A", "FS-047-prev2", "F-047", "Chak 47", listOf("Zone A", "Zone C"),
            date(19, 12, 2024, 11, 0), "Puma Super 75 EW", "Fenoxaprop-P-ethyl", HracGroup.G1,
            "500", "mL / acre", "Knapsack", "Tillering (GS 21-25)", OPERATOR, 3.1f, true, "100"
        ),
        TreatmentRecord(
            "T-2023-A", "FS-047-prev3", "F-047", "Chak 47", listOf("Zone A"),
            date(8, 1, 2024, 9, 45), "Topik 15 WP", "Clodinafop-propargyl", HracGroup.G1,
            "100", "g / acre", "Knapsack", "Tillering (GS 21-25)", OPERATOR, 2.8f, true, "100"
        ),
        TreatmentRecord(
            "T-2025-C", "FS-112-prev", "F-112", "Canal side", listOf("Zone A"),
            date(16, 12, 2025, 10, 10), "Leader 75 WG", "Sulfosulfuron", HracGroup.G2,
            "13", "g / acre", "Knapsack", "Tillering (GS 21-25)", OPERATOR, 0.9f, true, "100"
        )
    )

    fun priorFor(fieldId: String) = priorTreatments.filter { it.fieldId == fieldId }

    val applicationModes = listOf("Knapsack sprayer", "Tractor boom", "Mist blower", "Drone (contractor)")
    val growthStages = listOf(
        "Seedling (GS 11-13)", "Tillering (GS 21-25)", "Stem elongation (GS 30-32)", "Booting (GS 41-45)"
    )
    val doseUnits = listOf("g / acre", "mL / acre", "L / acre", "kg / acre")

    /** Efficacy trend for the same HRAC group on this field, spec section 41.2. */
    val rotationHistory = listOf(
        Triple("2023-24", HracGroup.G1, 81),
        Triple("2024-25", HracGroup.G1, 64),
        Triple("2025-26", HracGroup.G1, 47)
    )

    /** Conditions cached at 06:10 before leaving signal. Spraying is a weather decision. */
    data class Conditions(
        val tempC: Int, val windKmh: Int, val gustKmh: Int, val windFrom: String,
        val humidity: Int, val deltaT: Float, val rainFreeHours: Int, val updated: Long
    )

    val conditions = Conditions(14, 7, 12, "NW", 64, 4.2f, 36, date(22, 1, 2027, 6, 10))
}
