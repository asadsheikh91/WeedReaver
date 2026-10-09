"""Did the treatment work? Per zone, never as a field mean (spec section 41.2).

A field mean hides the zone where control failed, which is the zone that matters. Control in a
zone is the drop in weed cover over the zone's own footprint between the pre-treatment survey
and the follow-up survey.
"""

from __future__ import annotations

from dataclasses import dataclass

from app.analysis.polygonize import Polygon, footprint_mask
from app.analysis.raster import Grid
from app.analysis.surface import WeedSurface
from app.domain.enums import ACCEPTABLE_CONTROL_PCT


@dataclass
class ZoneEfficacy:
    letter: str
    before_pct: float
    after_pct: float
    efficacy_pct: int

    @property
    def inspect(self) -> bool:
        return self.efficacy_pct < ACCEPTABLE_CONTROL_PCT

    def to_json(self) -> dict:
        return {
            "letter": self.letter,
            "label": f"Zone {self.letter}",
            "beforePct": round(self.before_pct, 2),
            "afterPct": round(self.after_pct, 2),
            "efficacyPct": self.efficacy_pct,
            "inspect": self.inspect,
        }


def zone_efficacy(letter: str, polygons: list[Polygon], pre: WeedSurface, post: WeedSurface) -> ZoneEfficacy | None:
    xs, ys = footprint_mask(polygons, 1.0)
    if xs.size == 0:
        return None
    before = float(pre.values(xs, ys).mean()) * 100.0
    after = float(post.values(xs, ys).mean()) * 100.0
    if before <= 0:
        return None
    pct = round((1.0 - after / before) * 100.0)
    return ZoneEfficacy(letter=letter, before_pct=before, after_pct=after, efficacy_pct=max(0, min(100, pct)))


def field_delta_pct(before: Grid, after: Grid) -> int:
    """Change in prescribed cells between the two flights (positive = fewer cells to spray)."""
    return round((1.0 - after.flagged / max(1, before.flagged)) * 100.0)


def chemical_saved_fraction(before: Grid) -> float:
    """Spot spraying the prescription instead of a blanket spray. Measured against a
    blanket-spray baseline; it is not a yield claim."""
    return 1.0 - before.treated_fraction
