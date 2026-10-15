"""survey image GPS position

Revision ID: 0002
Revises: 0001
Create Date: 2026-10-14 09:00:00.000000

Each uploaded flight image keeps the GPS position read from its EXIF block, so a flight that
cannot be georeferenced (no GPS) or was uploaded against the wrong field is caught before
photogrammetry starts.
"""
from __future__ import annotations

from collections.abc import Sequence

import sqlalchemy as sa
from alembic import op


revision: str = '0002'
down_revision: str | None = '0001'
branch_labels: str | Sequence[str] | None = None
depends_on: str | Sequence[str] | None = None


def upgrade() -> None:
    with op.batch_alter_table('survey_images', schema=None) as batch_op:
        batch_op.add_column(sa.Column('lat', sa.Float(), nullable=True))
        batch_op.add_column(sa.Column('lon', sa.Float(), nullable=True))
        batch_op.add_column(sa.Column('alt_m', sa.Float(), nullable=True))


def downgrade() -> None:
    with op.batch_alter_table('survey_images', schema=None) as batch_op:
        batch_op.drop_column('alt_m')
        batch_op.drop_column('lon')
        batch_op.drop_column('lat')
