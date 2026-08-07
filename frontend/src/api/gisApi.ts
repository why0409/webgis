import type { FeatureCollection } from 'geojson'

const BASE = import.meta.env.VITE_API_BASE_URL || ''

export async function fetchShushanBoundary(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/boundaries/shushan`)
  return res.json()
}

export async function fetchPois(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/pois`)
  return res.json()
}

/** 林地/绿地面数据。category 可选：forest|wood|grass|park|other */
export async function fetchForests(category?: string): Promise<FeatureCollection> {
  const url = category
    ? `${BASE}/api/forests?category=${encodeURIComponent(category)}`
    : `${BASE}/api/forests`
  const res = await fetch(url)
  return res.json()
}

/** 林业统计：各分类面积（公顷）和数量 */
export interface ForestStat {
  category: string
  count: number
  area_ha: number
}
export async function fetchForestStats(): Promise<ForestStat[]> {
  const res = await fetch(`${BASE}/api/forests/stats`)
  return res.json()
}

/** 林业相关 POI 点位 */
export async function fetchForestPois(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/forests/pois`)
  return res.json()
}

/** Stage 3: 街道/乡镇绿化率分级 (Choropleth) 数据 */
export async function fetchSubdistrictChoropleth(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/subdistricts/choropleth`)
  return res.json()
}

/** Stage 4: 3D 建筑物高度数据 */
export async function fetchBuildings(): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/buildings`)
  return res.json()
}

/** Stage 5: 空间缓冲区探针检索（附近林地/绿地） */
export async function fetchNearbyForests(lng: number, lat: number, radius = 1000): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/spatial/nearby/forests?lng=${lng}&lat=${lat}&radius=${radius}`)
  return res.json()
}

/** Stage 5: 空间缓冲区探针检索（附近林业 POI） */
export async function fetchNearbyForestPois(lng: number, lat: number, radius = 1000): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/spatial/nearby/pois?lng=${lng}&lat=${lat}&radius=${radius}`)
  return res.json()
}

/** Stage 6: 视口 BBOX 按需加载数据 */
export async function fetchForestsByBbox(minLng: number, minLat: number, maxLng: number, maxLat: number): Promise<FeatureCollection> {
  const res = await fetch(`${BASE}/api/forests/bbox?minLng=${minLng}&minLat=${minLat}&maxLng=${maxLng}&maxLat=${maxLat}`)
  return res.json()
}



