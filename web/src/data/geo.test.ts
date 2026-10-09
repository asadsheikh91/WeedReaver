import { describe, expect, it } from 'vitest'
import { measureLatLon, toLatLon } from './geo'

// Chak 47 in its local frame, anchored where the backend seeds it.
const ANCHOR = { lat: 31.8942, lon: 73.2711 }
const CHAK_47 = [[22, 0], [243, 0], [245, 142], [1, 143], [0, 24], [22, 24]].map(([x, y]) => toLatLon(ANCHOR, { x, y }))

describe('measureLatLon', () => {
  it('measures an outline clicked on the map exactly as the backend measures the parcel', () => {
    const m = measureLatLon(CHAK_47.map(([lat, lon]) => ({ lat, lon })))
    expect(m.acres.toFixed(2)).toBe('8.45')
    expect(Math.round(m.perimeterM)).toBe(772)
  })

  it('reports nothing measurable for fewer than three corners', () => {
    expect(measureLatLon([{ lat: 31.9, lon: 73.27 }, { lat: 31.91, lon: 73.27 }])).toEqual({ sqm: 0, acres: 0, perimeterM: 0 })
  })

  it('flags an outline whose edges cross', () => {
    const bowtie = [{ lat: 31.9, lon: 73.27 }, { lat: 31.899, lon: 73.271 }, { lat: 31.9, lon: 73.271 }, { lat: 31.899, lon: 73.27 }]
    expect(measureLatLon(bowtie).crosses).toBe(true)
    expect(measureLatLon(CHAK_47.map(([lat, lon]) => ({ lat, lon }))).crosses).toBeFalsy()
  })
})
