import type { FeatureCollection } from 'geojson'

const BASE = import.meta.env.VITE_API_BASE_URL

export async function fetchShushanBoundary(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/boundaries/shushan`)
  return res.json()
}

export async function fetchPois(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/pois`)
  return res.json()
}
