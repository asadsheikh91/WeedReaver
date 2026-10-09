"""Domain vocabulary. Values are the exact strings the field app and dashboard already use."""

from __future__ import annotations

from enum import StrEnum


class Role(StrEnum):
    ADMIN = "ADMIN"
    ANALYST = "ANALYST"
    OPERATOR = "OPERATOR"
    TRAINEE = "TRAINEE"


ROLE_LABEL = {
    Role.ADMIN: "Administrator",
    Role.ANALYST: "Analyst",
    Role.OPERATOR: "Field operator",
    Role.TRAINEE: "Trainee",
}

#: Roles that decide (the dashboard surface). The others observe (the phone surface).
DECIDING_ROLES = frozenset({Role.ADMIN, Role.ANALYST})


class WeedClass(StrEnum):
    """Spec section 21. The aerial model predicts three classes, never a species."""

    CROP = "CROP"
    GRASS = "GRASS"
    BROADLEAF = "BROADLEAF"


WEED_CLASS_META = {
    WeedClass.CROP: {"label": "Crop canopy", "short": "Crop", "hint": "No action indicated"},
    WeedClass.GRASS: {
        "label": "Grass weed",
        "short": "Grass",
        "hint": "Points to ACCase (HRAC 1) or ALS (HRAC 2) chemistry",
    },
    WeedClass.BROADLEAF: {
        "label": "Broadleaf weed",
        "short": "Broadleaf",
        "hint": "Points to a different mode-of-action group",
    },
}

#: Raster class codes used by segmentation output (uint8 class maps).
CLASS_CODES = (WeedClass.CROP, WeedClass.GRASS, WeedClass.BROADLEAF)


class Severity(StrEnum):
    """Spec section 38. Bands are a decision aid, not a measurement."""

    CLEAN = "CLEAN"
    MODERATE = "MODERATE"
    HEAVY = "HEAVY"


SEVERITY_META = {
    Severity.CLEAN: {"label": "Clean", "meaning": "No treatment indicated"},
    Severity.MODERATE: {"label": "Moderate", "meaning": "Spot treatment indicated"},
    Severity.HEAVY: {"label": "Heavy", "meaning": "Likely control failure — investigate"},
}


def band(infest_pct: float) -> Severity:
    if infest_pct < 10:
        return Severity.CLEAN
    if infest_pct <= 30:
        return Severity.MODERATE
    return Severity.HEAVY


class SurveyRole(StrEnum):
    """Spec section 36.3: surveys carry a role, never an inferred timestamp comparison."""

    PRE = "PRE"
    PLUS_14D = "PLUS_14D"
    PLUS_28D = "PLUS_28D"


SURVEY_ROLE_META = {
    SurveyRole.PRE: {"label": "Pre-treatment", "short": "Pre", "offsetDays": 0},
    SurveyRole.PLUS_14D: {"label": "Follow-up +14 d", "short": "+14 d", "offsetDays": 14},
    SurveyRole.PLUS_28D: {"label": "Follow-up +28 d", "short": "+28 d", "offsetDays": 28},
}


class SurveyStatus(StrEnum):
    SCHEDULED = "SCHEDULED"
    QUEUED = "QUEUED"
    PROCESSING = "PROCESSING"
    READY = "READY"
    FAILED = "FAILED"


#: A survey in one of these states holds its role for the season.
ACTIVE_SURVEY_STATUSES = frozenset({SurveyStatus.QUEUED, SurveyStatus.PROCESSING, SurveyStatus.READY})


class ZoneState(StrEnum):
    """Spec section 43.1. The one object both surfaces share."""

    FLAGGED = "FLAGGED"
    ROUTED = "ROUTED"
    TREATED = "TREATED"
    RESURVEYED = "RESURVEYED"

    @property
    def done(self) -> bool:
        return self in (ZoneState.TREATED, ZoneState.RESURVEYED)


ZONE_STATE_LABEL = {
    ZoneState.FLAGGED: "Flagged",
    ZoneState.ROUTED: "Routed",
    ZoneState.TREATED: "Treated",
    ZoneState.RESURVEYED: "Re-surveyed",
}

GRID_SIZES = (1, 2, 5)
GRID_ACTUATOR = {
    1: "Knapsack operator on a navigation prompt",
    2: "Section control on a tractor boom",
    5: "Robust to unassisted smartphone GNSS drift",
}


class HracGroup(StrEnum):
    """Spec section 41.1, HRAC 2020 global classification."""

    G1 = "G1"
    G2 = "G2"
    G3 = "G3"
    G4 = "G4"
    G5 = "G5"
    G9 = "G9"
    G15 = "G15"

    @property
    def code(self) -> str:
        return self.value[1:]

    @property
    def moa(self) -> str:
        return HRAC_MOA[self]

    @property
    def display(self) -> str:
        return f"Group {self.code} · {self.moa}"


HRAC_MOA = {
    HracGroup.G1: "ACCase inhibitor",
    HracGroup.G2: "ALS inhibitor",
    HracGroup.G3: "Microtubule inhibitor",
    HracGroup.G4: "Auxin mimic",
    HracGroup.G5: "PS II inhibitor",
    HracGroup.G9: "EPSP synthase inhibitor",
    HracGroup.G15: "VLCFA inhibitor",
}

APPLICATION_MODES = ("Knapsack sprayer", "Tractor boom", "Mist blower", "Drone (contractor)")
GROWTH_STAGES = (
    "Seedling (GS 11-13)",
    "Tillering (GS 21-25)",
    "Stem elongation (GS 30-32)",
    "Booting (GS 41-45)",
)
DOSE_UNITS = ("g / acre", "mL / acre", "L / acre", "kg / acre")
CAPTURE_METHODS = ("Surveyed", "Walked", "Drawn", "Imported")


class ExportFormat(StrEnum):
    GEOJSON = "GeoJSON"
    SHAPEFILE = "Shapefile"
    TASKDATA = "TASKDATA"


class JobStatus(StrEnum):
    QUEUED = "QUEUED"
    RUNNING = "RUNNING"
    SUCCEEDED = "SUCCEEDED"
    FAILED = "FAILED"


class ScanStatus(StrEnum):
    """Where a leaf scan sits in the review loop (spec section 26)."""

    CONFIDENT = "CONFIDENT"  # the model answered
    NEEDS_LABEL = "NEEDS_LABEL"  # abstained, nobody has looked yet
    ANNOTATED = "ANNOTATED"  # the operator suggested a label in the field
    RESOLVED = "RESOLVED"  # the analyst decided


#: Acceptable control reference used by the agronomist (rotation chart dashed line).
ACCEPTABLE_CONTROL_PCT = 70
ACRE_SQM = 4046.86
