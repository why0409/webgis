/**
 * Stage 2: 向量瓦片图层管理
 *
 * Martin 服务（port 3000）直接暴露 PostGIS 表为 MVT 瓦片。
 * 本 composable 负责：
 *   1. 检测 Martin 服务是否可用
 *   2. 向 MapLibre 添加 vector source + layers（替代全量 GeoJSON）
 *   3. 对外暴露 isTileMode ref，供 UI 显示当前数据加载方式
 *
 * 用法：
 *   const { initVectorTileLayers, isTileMode } = useVectorTiles(map)
 *   await initVectorTileLayers()
 */

import { ref } from 'vue'
import type { Map as MaplibreMap } from 'maplibre-gl'

const MARTIN_BASE = 'http://localhost:3000'

// 分类颜色（与 MapView.vue 保持一致）
const CATEGORY_COLOR_EXPR = [
  'match', ['get', 'category'],
  'forest', '#1a7a1a',
  'wood',   '#3da838',
  'grass',  '#7ec850',
  'park',   '#2da44e',
  '#88b04b'   // other / fallback
] as unknown as maplibregl.ExpressionSpecification

export function useVectorTiles(map: MaplibreMap) {
  const isTileMode = ref(false)

  /** 检测 Martin 是否在线 */
  async function martinOnline(): Promise<boolean> {
    try {
      const res = await fetch(`${MARTIN_BASE}/health`, { signal: AbortSignal.timeout(2000) })
      return res.ok
    } catch {
      return false
    }
  }

  /**
   * 用向量瓦片图层替换（或补充）现有 GeoJSON forests 图层。
   * 若 Martin 离线则静默跳过，保持 GeoJSON 回退。
   */
  async function initVectorTileLayers(): Promise<void> {
    const online = await martinOnline()
    if (!online) {
      console.info('[VectorTiles] Martin 离线，保持 GeoJSON 模式')
      return
    }

    console.info('[VectorTiles] Martin 在线，切换为向量瓦片模式')
    isTileMode.value = true

    // ── 移除旧 GeoJSON forests 图层 & source ────────────────────
    ;['forests-fill', 'forests-line'].forEach(id => {
      if (map.getLayer(id)) map.removeLayer(id)
    })
    if (map.getSource('forests')) map.removeSource('forests')

    // ── osm_forests 向量瓦片 source ──────────────────────────────
    // Martin 自动将表 osm_forests 暴露为 /{table}/{z}/{x}/{y}
    map.addSource('forests-tiles', {
      type: 'vector',
      tiles: [`${MARTIN_BASE}/osm_forests/{z}/{x}/{y}`],
      minzoom: 5,
      maxzoom: 16,
      attribution: '© OpenStreetMap contributors'
    })

    // 面填充层
    map.addLayer({
      id: 'forests-fill',
      type: 'fill',
      source: 'forests-tiles',
      'source-layer': 'osm_forests',   // Martin 默认 source-layer 名 = 表名
      paint: {
        'fill-color': CATEGORY_COLOR_EXPR,
        'fill-opacity': [
          'interpolate', ['linear'], ['zoom'],
          8, 0.55,
          13, 0.35
        ]
      }
    })

    // 边界线层
    map.addLayer({
      id: 'forests-line',
      type: 'line',
      source: 'forests-tiles',
      'source-layer': 'osm_forests',
      paint: {
        'line-color': CATEGORY_COLOR_EXPR,
        'line-width': [
          'interpolate', ['linear'], ['zoom'],
          8, 0.5,
          13, 1.5
        ],
        'line-opacity': 0.8
      }
    })

    // ── osm_forest_pois 向量瓦片 source ──────────────────────────
    ;['forest-pois-circle'].forEach(id => {
      if (map.getLayer(id)) map.removeLayer(id)
    })
    if (map.getSource('forest-pois')) map.removeSource('forest-pois')

    map.addSource('forest-pois-tiles', {
      type: 'vector',
      tiles: [`${MARTIN_BASE}/osm_forest_pois/{z}/{x}/{y}`],
      minzoom: 5,
      maxzoom: 16
    })

    map.addLayer({
      id: 'forest-pois-circle',
      type: 'circle',
      source: 'forest-pois-tiles',
      'source-layer': 'osm_forest_pois',
      minzoom: 6,
      paint: {
        'circle-radius': [
          'interpolate', ['linear'], ['zoom'],
          8, 5,
          14, 8
        ],
        'circle-color': '#00e676',
        'circle-stroke-width': 2,
        'circle-stroke-color': '#064e3b'
      }
    })

    // ── osm_buildings 3D 建筑物矢量瓦片图层 ──────────────────────
    ;['buildings-extrusion'].forEach(id => {
      if (map.getLayer(id)) map.removeLayer(id)
    })
    if (map.getSource('buildings-tiles')) map.removeSource('buildings-tiles')

    map.addSource('buildings-tiles', {
      type: 'vector',
      tiles: [`${MARTIN_BASE}/osm_buildings/{z}/{x}/{y}`],
      minzoom: 12,
      maxzoom: 16
    })

    map.addLayer({
      id: 'buildings-extrusion',
      type: 'fill-extrusion',
      source: 'buildings-tiles',
      'source-layer': 'osm_buildings',
      minzoom: 13,
      paint: {
        'fill-extrusion-color': [
          'interpolate', ['linear'], ['get', 'height'],
          0, '#f1f5f9',
          20, '#cbd5e1',
          50, '#94a3b8',
          100, '#64748b'
        ],
        'fill-extrusion-height': ['coalesce', ['get', 'height'], 15],
        'fill-extrusion-base': ['coalesce', ['get', 'min_height'], 0],
        'fill-extrusion-opacity': 0.85
      }
    })
  }

  return { isTileMode, initVectorTileLayers }
}
