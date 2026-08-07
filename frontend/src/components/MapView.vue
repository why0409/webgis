<script setup lang="ts">
import { onMounted, ref, reactive } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import {
  fetchShushanBoundary,
  fetchPois,
  fetchForests,
  fetchForestStats,
  fetchForestPois,
  fetchSubdistrictChoropleth,
  fetchBuildings,
  fetchNearbyForests,
  fetchNearbyForestPois,
  type ForestStat
} from '../api/gisApi'
import type { FeatureCollection } from 'geojson'
import { useVectorTiles } from '../composables/useVectorTiles'

// ── 地图容器 ──────────────────────────────────────────────────
const mapContainer = ref<HTMLDivElement>()

// ── 图层可见性开关 ────────────────────────────────────────────
const layers = reactive({
  boundary: true,
  pois: true,
  forests: true,
  forestPois: true,
  choropleth: true,
  buildings: true
})

// ── Stage 4: 2D / 3D 视角切换 ─────────────────────────────────
const is3D = ref(false)
function toggle3DView() {
  is3D.value = !is3D.value
  if (!map) return
  if (is3D.value) {
    map.easeTo({ pitch: 60, bearing: -20, duration: 1200 })
  } else {
    map.easeTo({ pitch: 0, bearing: 0, duration: 1200 })
  }
}

// ── Stage 5: 空间探针与缓冲区分析 (Spatial Probe) ────────────────
const isProbeActive = ref(false)
const probeRadius = ref(1000)
const probeCenter = ref<[number, number] | null>(null)
const probeResults = ref<Array<{
  name: string
  category: string
  distance_m: number
  area_ha: number
  osm_id: string
}>>([])
const probePois = ref<Array<{
  name: string
  poi_type: string
  distance_m: number
  osm_id: string
}>>([])
const probeLoading = ref(false)

function clearProbeBuffer() {
  probeCenter.value = null
  probeResults.value = []
  probePois.value = []
  if (!map) return
  if (map.getSource('probe-buffer')) {
    (map.getSource('probe-buffer') as maplibregl.GeoJSONSource).setData({
      type: 'FeatureCollection',
      features: []
    })
  }
}

function toggleProbeMode() {
  isProbeActive.value = !isProbeActive.value
  if (!map) return
  map.getCanvas().style.cursor = isProbeActive.value ? 'crosshair' : ''
  if (!isProbeActive.value) {
    clearProbeBuffer()
  }
}

async function runProbeQuery(lng: number, lat: number) {
  if (!map) return
  probeCenter.value = [lng, lat]
  probeLoading.value = true

  updateBufferCircleLayer(lng, lat, probeRadius.value)

  try {
    const [forestsRes, poisRes] = await Promise.all([
      fetchNearbyForests(lng, lat, probeRadius.value),
      fetchNearbyForestPois(lng, lat, probeRadius.value)
    ])

    probeResults.value = forestsRes.features.map(f => ({
      name: (f.properties?.name as string) || '未命名绿地',
      category: (f.properties?.category as string) || 'other',
      distance_m: Number(f.properties?.distance_m || 0),
      area_ha: Number(f.properties?.area_ha || 0),
      osm_id: (f.properties?.osm_id as string) || ''
    }))

    probePois.value = poisRes.features.map(f => ({
      name: (f.properties?.name as string) || '未命名节点',
      poi_type: (f.properties?.poi_type as string) || 'forest_poi',
      distance_m: Number(f.properties?.distance_m || 0),
      osm_id: (f.properties?.osm_id as string) || ''
    }))
  } catch (err) {
    console.error('[ProbeQuery] 空间检索失败:', err)
  } finally {
    probeLoading.value = false
  }
}

function updateBufferCircleLayer(lng: number, lat: number, radiusMeters: number) {
  if (!map) return
  const points = 64
  const coords: [number, number][] = []
  const km = radiusMeters / 1000
  const distanceX = km / (111.32 * Math.cos(lat * Math.PI / 180))
  const distanceY = km / 110.574

  for (let i = 0; i < points; i++) {
    const theta = (i / points) * (2 * Math.PI)
    const x = lng + distanceX * Math.cos(theta)
    const y = lat + distanceY * Math.sin(theta)
    coords.push([x, y])
  }
  coords.push(coords[0])

  const circleGeoJSON: FeatureCollection = {
    type: 'FeatureCollection',
    features: [{
      type: 'Feature',
      properties: {},
      geometry: {
        type: 'Polygon',
        coordinates: [coords]
      }
    }]
  }

  if (map.getSource('probe-buffer')) {
    (map.getSource('probe-buffer') as maplibregl.GeoJSONSource).setData(circleGeoJSON)
  } else {
    map.addSource('probe-buffer', { type: 'geojson', data: circleGeoJSON })
    map.addLayer({
      id: 'probe-buffer-fill',
      type: 'fill',
      source: 'probe-buffer',
      paint: {
        'fill-color': '#38bdf8',
        'fill-opacity': 0.18
      }
    })
    map.addLayer({
      id: 'probe-buffer-line',
      type: 'line',
      source: 'probe-buffer',
      paint: {
        'line-color': '#0284c7',
        'line-width': 2,
        'line-dasharray': [3, 2]
      }
    })
  }
}

function changeRadius(r: number) {
  probeRadius.value = r
  if (probeCenter.value) {
    runProbeQuery(probeCenter.value[0], probeCenter.value[1])
  }
}

// ── Stage 6: 视口 BBOX 按需加载 (Viewport Lazy Loading) ─────────
const isBboxMode = ref(false)
const bboxFeatureCount = ref(0)
let bboxDebounceTimer: any = null

function toggleBboxMode() {
  isBboxMode.value = !isBboxMode.value
  if (isBboxMode.value) {
    onViewportChange()
  } else {
    reloadAllForests()
  }
}

async function onViewportChange() {
  if (!map || !isBboxMode.value) return
  if (bboxDebounceTimer) clearTimeout(bboxDebounceTimer)

  bboxDebounceTimer = setTimeout(async () => {
    const bounds = map!.getBounds()
    const west  = bounds.getWest()
    const south = bounds.getSouth()
    const east  = bounds.getEast()
    const north = bounds.getNorth()

    try {
      const data = await fetchForestsByBbox(west, south, east, north)
      bboxFeatureCount.value = data.features.length
      if (map!.getSource('forests')) {
        (map!.getSource('forests') as maplibregl.GeoJSONSource).setData(data)
      }
    } catch (err) {
      console.error('[BBOX] 视口查询失败:', err)
    }
  }, 250)
}

async function reloadAllForests() {
  if (!map) return
  try {
    const data = await fetchForests()
    bboxFeatureCount.value = data.features.length
    if (map.getSource('forests')) {
      (map.getSource('forests') as maplibregl.GeoJSONSource).setData(data)
    }
  } catch (err) {
    console.error('[Forests] 刷新全量数据失败:', err)
  }
}

// ── 统计面板数据 ──────────────────────────────────────────────
const stats = ref<ForestStat[]>([])
const totalArea = ref(0)
const totalCount = ref(0)
const statsLoading = ref(true)

// ── 分类中文标签 & 颜色 ────────────────────────────────────────
const CATEGORY_LABEL: Record<string, string> = {
  forest: '林地',
  wood:   '树木/灌丛',
  grass:  '草地/绿地',
  park:   '公园/保护区',
  other:  '其他'
}

const CATEGORY_COLOR: Record<string, string> = {
  forest: '#1a7a1a',
  wood:   '#3da838',
  grass:  '#7ec850',
  park:   '#2da44e',
  other:  '#88b04b'
}

// ── Popup ─────────────────────────────────────────────────────
let popup: maplibregl.Popup | null = null

// ── MapLibre 实例（供图层开关使用） ──────────────────────────
let map: maplibregl.Map | null = null

// ── Stage 2: 向量瓦片（延迟初始化，map ready 后才能用） ─────
let isTileMode = ref(false)

// ── 图层可见性切换 ────────────────────────────────────────────
function toggleLayer(key: keyof typeof layers) {
  layers[key] = !layers[key]
  if (!map) return

  const visibility = layers[key] ? 'visible' : 'none'
  const layerIds: Record<keyof typeof layers, string[]> = {
    boundary:   ['shushan-boundary-fill', 'shushan-boundary-line'],
    pois:       ['pois-circle'],
    forests:    ['forests-fill', 'forests-line'],
    forestPois: ['forest-pois-circle'],
    choropleth: ['choropleth-fill', 'choropleth-line'],
    buildings:  ['buildings-extrusion']
  }
  layerIds[key].forEach(id => {
    if (map!.getLayer(id)) {
      map!.setLayoutProperty(id, 'visibility', visibility)
    }
  })
}

// ── 格式化面积数字 ────────────────────────────────────────────
function fmtArea(ha: number): string {
  if (ha >= 100) return `${ha.toFixed(0)} 公顷`
  return `${ha.toFixed(2)} 公顷`
}

onMounted(async () => {
  // 1. 创建地图
  map = new maplibregl.Map({
    container: mapContainer.value!,
    style: 'https://demotiles.maplibre.org/style.json',
    center: [117.20, 31.85],
    zoom: 11
  })

  map.addControl(new maplibregl.NavigationControl(), 'top-right')

  // 地图点击空间探针事件
  map.on('click', (e) => {
    if (isProbeActive.value) {
      runProbeQuery(e.lngLat.lng, e.lngLat.lat)
    }
  })

  // Stage 6: 监听视口平移/缩放，动态更新 BBOX 检索
  map.on('moveend', () => {
    if (isBboxMode.value) {
      onViewportChange()
    }
  })

  map.on('load', async () => {
    // ── A. 行政边界 ──────────────────────────────────────────
    const boundary = await fetchShushanBoundary()
    map!.addSource('shushan-boundary', { type: 'geojson', data: boundary })
    map!.addLayer({
      id: 'shushan-boundary-fill',
      type: 'fill',
      source: 'shushan-boundary',
      paint: { 'fill-color': '#ff6600', 'fill-opacity': 0.05 }
    })
    map!.addLayer({
      id: 'shushan-boundary-line',
      type: 'line',
      source: 'shushan-boundary',
      paint: { 'line-color': '#ff6600', 'line-width': 2.5, 'line-dasharray': [4, 2] }
    })

    // ── B. 林地/绿地面图层（按 category 颜色渲染）────────────
    const forests = await fetchForests()
    map!.addSource('forests', { type: 'geojson', data: forests })
    map!.addLayer({
      id: 'forests-fill',
      type: 'fill',
      source: 'forests',
      paint: {
        'fill-color': [
          'match', ['get', 'category'],
          'forest', CATEGORY_COLOR.forest,
          'wood',   CATEGORY_COLOR.wood,
          'grass',  CATEGORY_COLOR.grass,
          'park',   CATEGORY_COLOR.park,
          CATEGORY_COLOR.other
        ],
        'fill-opacity': 0.45
      }
    })
    map!.addLayer({
      id: 'forests-line',
      type: 'line',
      source: 'forests',
      paint: {
        'line-color': [
          'match', ['get', 'category'],
          'forest', CATEGORY_COLOR.forest,
          'wood',   CATEGORY_COLOR.wood,
          'grass',  CATEGORY_COLOR.grass,
          'park',   CATEGORY_COLOR.park,
          CATEGORY_COLOR.other
        ],
        'line-width': 1,
        'line-opacity': 0.7
      }
    })

    // ── C. 林地面 Popup ───────────────────────────────────────
    map!.on('click', 'forests-fill', (e) => {
      const f = e.features?.[0]
      if (!f) return
      const p = f.properties as Record<string, string | number>
      const catLabel = CATEGORY_LABEL[p.category as string] ?? p.category
      const areaHa = p.area_m2 ? (Number(p.area_m2) / 10000).toFixed(2) : '—'
      const name = p.name || '（无名称）'

      popup?.remove()
      popup = new maplibregl.Popup({ closeButton: true, maxWidth: '280px' })
        .setLngLat(e.lngLat)
        .setHTML(`
          <div class="map-popup">
            <div class="popup-title">${name}</div>
            <table class="popup-table">
              <tr><td>类型</td><td>${catLabel}</td></tr>
              ${p.landuse  ? `<tr><td>landuse</td><td>${p.landuse}</td></tr>` : ''}
              ${p.leisure  ? `<tr><td>leisure</td><td>${p.leisure}</td></tr>` : ''}
              ${p.natural  ? `<tr><td>natural</td><td>${p.natural}</td></tr>` : ''}
              <tr><td>面积</td><td>${areaHa} 公顷</td></tr>
              <tr><td>OSM ID</td><td>${p.osm_id ?? '—'}</td></tr>
            </table>
          </div>`)
        .addTo(map!)
    })

    map!.on('mouseenter', 'forests-fill', () => {
      map!.getCanvas().style.cursor = 'pointer'
    })
    map!.on('mouseleave', 'forests-fill', () => {
      map!.getCanvas().style.cursor = ''
    })

    // ── D. 设施 POI（按 amenity 类型分色） ──────────────────────
    // 分组色彩方案：
    //   餐饮 orange | 金融 purple | 教育 sky | 医疗 red
    //   交通 slate  | 政务 indigo | 公共 teal | 娱乐 amber
    const pois = await fetchPois()
    map!.addSource('pois', { type: 'geojson', data: pois })
    map!.addLayer({
      id: 'pois-circle',
      type: 'circle',
      source: 'pois',
      paint: {
        'circle-radius': [
          'match', ['get', 'amenity'],
          // 较大：重要公共设施
          ['hospital', 'college', 'townhall', 'police'], 7,
          // 中等：常用设施
          ['restaurant', 'cafe', 'bank', 'school', 'clinic', 'pharmacy', 'parking'], 5,
          // 默认小点
          4
        ],
        'circle-color': [
          'match', ['get', 'amenity'],
          // 🍜 餐饮
          ['restaurant', 'cafe', 'fast_food', 'bar', 'pub', 'food_court'], '#f97316',
          // 🏦 金融/服务
          ['bank', 'atm', 'post_office', 'post_box'], '#a855f7',
          // 🎓 教育
          ['college', 'university', 'school', 'kindergarten', 'research_institute', 'library'], '#0ea5e9',
          // 🏥 医疗
          ['hospital', 'clinic', 'pharmacy', 'dentist', 'doctors', 'public_bath'], '#ef4444',
          // 🚗 交通
          ['parking', 'parking_entrance', 'bicycle_parking', 'bicycle_repair_station',
           'charging_station', 'car_wash', 'fuel', 'taxi'], '#64748b',
          // 🏛️ 政务/安全
          ['townhall', 'police', 'fire_station', 'courthouse', 'embassy'], '#4f46e5',
          // 🎭 娱乐文化
          ['cinema', 'theatre', 'exhibition_centre', 'community_centre', 'arts_centre'], '#eab308',
          // 🌿 公共设施
          ['toilets', 'waste_basket', 'shelter', 'fountain', 'bench', 'internet_cafe'], '#14b8a6',
          // 其他 → 中灰
          '#94a3b8'
        ],
        'circle-stroke-width': 1.5,
        'circle-stroke-color': '#ffffff',
        'circle-opacity': 0.9
      }
    })

    // 设施 POI Popup
    map!.on('click', 'pois-circle', (e) => {
      const f = e.features?.[0]
      if (!f) return
      const p = f.properties as Record<string, string>
      popup?.remove()
      popup = new maplibregl.Popup({ closeButton: true, maxWidth: '240px' })
        .setLngLat(e.lngLat)
        .setHTML(`
          <div class="map-popup">
            <div class="popup-title">${p.name || '（无名称）'}</div>
            <table class="popup-table">
              <tr><td>设施类型</td><td>${p.amenity ?? '—'}</td></tr>
              <tr><td>OSM ID</td><td>${p.osm_id ?? '—'}</td></tr>
            </table>
          </div>`)
        .addTo(map!)
    })
    map!.on('mouseenter', 'pois-circle', () => {
      map!.getCanvas().style.cursor = 'pointer'
    })
    map!.on('mouseleave', 'pois-circle', () => {
      map!.getCanvas().style.cursor = ''
    })

    // ── E. 林业 POI 点 ────────────────────────────────────────
    const forestPois = await fetchForestPois()
    map!.addSource('forest-pois', { type: 'geojson', data: forestPois })
    map!.addLayer({
      id: 'forest-pois-circle',
      type: 'circle',
      source: 'forest-pois',
      paint: {
        'circle-radius': 6,
        'circle-color': '#00e676',
        'circle-stroke-width': 2,
        'circle-stroke-color': '#064e3b'
      }
    })

    // ── F. 林业 POI Popup ─────────────────────────────────────
    map!.on('click', 'forest-pois-circle', (e) => {
      const f = e.features?.[0]
      if (!f) return
      const p = f.properties as Record<string, string>
      popup?.remove()
      popup = new maplibregl.Popup({ closeButton: true, maxWidth: '260px' })
        .setLngLat(e.lngLat)
        .setHTML(`
          <div class="map-popup">
            <div class="popup-title">${p.name || '（无名称）'}</div>
            <table class="popup-table">
              <tr><td>类型</td><td>${p.poi_type ?? '—'}</td></tr>
              ${p.landuse ? `<tr><td>landuse</td><td>${p.landuse}</td></tr>` : ''}
              ${p.leisure ? `<tr><td>leisure</td><td>${p.leisure}</td></tr>` : ''}
              ${p.natural ? `<tr><td>natural</td><td>${p.natural}</td></tr>` : ''}
              <tr><td>OSM ID</td><td>${p.osm_id ?? '—'}</td></tr>
            </table>
          </div>`)
        .addTo(map!)
    })

    map!.on('mouseenter', 'forest-pois-circle', () => {
      map!.getCanvas().style.cursor = 'pointer'
    })
    map!.on('mouseleave', 'forest-pois-circle', () => {
      map!.getCanvas().style.cursor = ''
    })

    // ── G. Stage 3: 街道绿化率专题图 (Choropleth Layer) ─────────────────
    try {
      const choroplethData = await fetchSubdistrictChoropleth()
      map!.addSource('subdistrict-choropleth', { type: 'geojson', data: choroplethData })

      map!.addLayer({
        id: 'choropleth-fill',
        type: 'fill',
        source: 'subdistrict-choropleth',
        paint: {
          'fill-color': [
            'interpolate', ['linear'], ['get', 'green_rate_pct'],
            0,   '#f0fdf4',
            15,  '#bbf7d0',
            30,  '#4ade80',
            45,  '#16a34a',
            60,  '#14532d'
          ],
          'fill-opacity': 0.55
        }
      }, 'shushan-boundary-line')

      map!.addLayer({
        id: 'choropleth-line',
        type: 'line',
        source: 'subdistrict-choropleth',
        paint: {
          'line-color': '#064e3b',
          'line-width': 1.5,
          'line-dasharray': [2, 2]
        }
      })

      // 街道绿化 Popup
      map!.on('click', 'choropleth-fill', (e) => {
        const f = e.features?.[0]
        if (!f) return
        const p = f.properties as Record<string, string | number>
        popup?.remove()
        popup = new maplibregl.Popup({ closeButton: true, maxWidth: '280px' })
          .setLngLat(e.lngLat)
          .setHTML(`
            <div class="map-popup">
              <div class="popup-title">🏙️ ${p.name || '未命名街道'}</div>
              <table class="popup-table">
                <tr><td>街道绿化率</td><td><b style="color:#059669">${p.green_rate_pct}%</b> (${p.green_grade})</td></tr>
                <tr><td>辖区总面积</td><td>${p.total_area_ha} 公顷</td></tr>
                <tr><td>绿地交集面积</td><td>${p.green_area_ha} 公顷</td></tr>
                <tr><td>OSM ID</td><td>${p.osm_id ?? '—'}</td></tr>
              </table>
            </div>`)
          .addTo(map!)
      })
      map!.on('mouseenter', 'choropleth-fill', () => { map!.getCanvas().style.cursor = 'pointer' })
      map!.on('mouseleave', 'choropleth-fill', () => { map!.getCanvas().style.cursor = '' })
    } catch (err) {
      console.warn('[Choropleth] 街道绿化数据暂未加载:', err)
    }

    // ── H. Stage 4: 3D 建筑物 GeoJSON 兜底图层 ───────────────────────
    try {
      const buildingsData = await fetchBuildings()
      map!.addSource('buildings', { type: 'geojson', data: buildingsData })
      map!.addLayer({
        id: 'buildings-extrusion',
        type: 'fill-extrusion',
        source: 'buildings',
        minzoom: 12,
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
    } catch (err) {
      console.warn('[Buildings] 建筑物数据暂未加载:', err)
    }

    // ── Stage 2: 向量瓦片（Martin 在线则替换 GeoJSON forests 图层） ─────
    const vt = useVectorTiles(map!)
    isTileMode = vt.isTileMode
    await vt.initVectorTileLayers()
  })

  // ── G. 统计数据（独立于地图 load） ───────────────────────────
  try {
    const s = await fetchForestStats()
    stats.value = s
    totalArea.value  = s.reduce((acc, r) => acc + r.area_ha, 0)
    totalCount.value = s.reduce((acc, r) => acc + r.count, 0)
  } catch {
    // 后端尚未运行时静默处理
  } finally {
    statsLoading.value = false
  }
})
</script>

<template>
  <div class="page-wrapper">

    <!-- ── 顶部 2D / 3D 视角切换与空间探针工具栏 ──────────────── -->
    <div class="view-mode-bar">
      <button class="view-btn" :class="{ active: is3D }" @click="toggle3DView">
        {{ is3D ? '🏙️ 3D 俯瞰视角' : '🗺️ 2D 正投视角' }}
      </button>
      <button class="view-btn probe-btn" :class="{ active: isProbeActive }" @click="toggleProbeMode">
        {{ isProbeActive ? '🎯 探针模式已开启 (点击地图)' : '🎯 空间缓冲区探针' }}
      </button>
      <button class="view-btn bbox-btn" :class="{ active: isBboxMode }" @click="toggleBboxMode">
        {{ isBboxMode ? `⚡ 视口按需加载中 (${bboxFeatureCount}个)` : '⚡ 视口按需加载 (BBOX)' }}
      </button>
    </div>

    <!-- ── 地图容器 ────────────────────────────────────────── -->
    <div ref="mapContainer" class="map-container"></div>

    <!-- ── 图层控制面板 ──────────────────────────────────────── -->
    <div class="layer-panel">
      <div class="panel-title">🗺️ 图层控制</div>

      <label class="layer-item" :class="{ off: !layers.boundary }">
        <input type="checkbox" :checked="layers.boundary" @change="toggleLayer('boundary')" />
        <span class="layer-dot" style="background:#ff6600"></span>
        行政边界
      </label>

      <label class="layer-item" :class="{ off: !layers.forests }">
        <input type="checkbox" :checked="layers.forests" @change="toggleLayer('forests')" />
        <span class="layer-dot" style="background:#2da44e"></span>
        林地/绿地
      </label>

      <label class="layer-item" :class="{ off: !layers.forestPois }">
        <input type="checkbox" :checked="layers.forestPois" @change="toggleLayer('forestPois')" />
        <span class="layer-dot" style="background:#00e676; border: 1.5px solid #064e3b"></span>
        林业 POI
      </label>

      <label class="layer-item" :class="{ off: !layers.choropleth }">
        <input type="checkbox" :checked="layers.choropleth" @change="toggleLayer('choropleth')" />
        <span class="layer-dot" style="background: linear-gradient(to right, #bbf7d0, #14532d)"></span>
        街道绿化率专题图
      </label>

      <label class="layer-item" :class="{ off: !layers.buildings }">
        <input type="checkbox" :checked="layers.buildings" @change="toggleLayer('buildings')" />
        <span class="layer-dot" style="background:#94a3b8"></span>
        3D 建筑白模
      </label>

      <!-- 街道绿化率渐变图例 -->
      <div v-if="layers.choropleth" class="choropleth-legend">
        <div class="legend-title" style="margin-top:4px">绿化率 (%)</div>
        <div class="ramp-bar"></div>
        <div class="ramp-labels">
          <span>0%</span>
          <span>15%</span>
          <span>30%</span>
          <span>45%+</span>
        </div>
      </div>

      <label class="layer-item" :class="{ off: !layers.pois }">
        <input type="checkbox" :checked="layers.pois" @change="toggleLayer('pois')" />
        <span class="layer-dot" style="background: linear-gradient(135deg,#f97316 33%,#0ea5e9 33% 66%,#ef4444 66%)"></span>
        设施 POI
      </label>

      <!-- 设施 POI 分类图例（仅图层开启时展示） -->
      <div v-if="layers.pois" class="poi-legend">
        <div class="poi-legend-item"><span class="poi-dot" style="background:#f97316"></span>餐饮</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#0ea5e9"></span>教育</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#ef4444"></span>医疗</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#a855f7"></span>金融</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#4f46e5"></span>政务</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#64748b"></span>交通</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#eab308"></span>娱乐</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#14b8a6"></span>公共</div>
        <div class="poi-legend-item"><span class="poi-dot" style="background:#94a3b8"></span>其他</div>
      </div>

      <!-- 林地图例 -->
      <div class="legend-title">图例（林地分类）</div>
      <div v-for="(color, cat) in CATEGORY_COLOR" :key="cat" class="legend-item">
        <span class="legend-color" :style="{ background: color }"></span>
        {{ CATEGORY_LABEL[cat] ?? cat }}
      </div>

      <!-- 数据模式 badge -->
      <div class="mode-badge" :class="isTileMode ? 'tile' : 'geojson'">
        {{ isTileMode ? '⚡ 向量瓦片模式' : '📦 GeoJSON 模式' }}
      </div>

      <!-- Stage 6: BBOX 视口数据计数 badge -->
      <div v-if="isBboxMode" class="bbox-badge">
        🎯 视口绿地: {{ bboxFeatureCount }} 个 (PostGIS ST_MakeEnvelope)
      </div>
    </div>

    <!-- ── 统计面板 ──────────────────────────────────────────── -->
    <div class="stats-panel">
      <div class="panel-title">🌲 林业专题统计</div>

      <div v-if="statsLoading" class="stats-loading">加载中…</div>

      <template v-else>
        <div class="stats-summary">
          <div class="stat-card">
            <div class="stat-value">{{ totalCount }}</div>
            <div class="stat-label">绿地斑块数</div>
          </div>
          <div class="stat-card">
            <div class="stat-value">{{ fmtArea(totalArea) }}</div>
            <div class="stat-label">绿地总面积</div>
          </div>
        </div>

        <table class="stats-table" v-if="stats.length > 0">
          <thead>
            <tr>
              <th>类型</th>
              <th>数量</th>
              <th>面积(公顷)</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="row in stats" :key="row.category">
              <td>
                <span class="cat-dot" :style="{ background: CATEGORY_COLOR[row.category] ?? '#888' }"></span>
                {{ CATEGORY_LABEL[row.category] ?? row.category }}
              </td>
              <td>{{ row.count }}</td>
              <td>{{ row.area_ha.toFixed(2) }}</td>
            </tr>
          </tbody>
        </table>

        <div v-else class="stats-empty">
          暂无数据<br>
          <small>请先运行数据抓取 & 导入脚本</small>
        </div>
      </template>
    </div>

    <!-- ── Stage 5: 空间探针检索结果面板 ────────────────────────── -->
    <div v-if="isProbeActive && probeCenter" class="probe-panel">
      <div class="panel-title" style="display:flex; justify-content:space-between; align-items:center;">
        <span>🎯 空间缓冲区检索结果</span>
        <button class="panel-close-btn" @click="toggleProbeMode" title="关闭探针模式并清除图层">✕</button>
      </div>

      <div class="probe-target-info">
        中心点: <code>{{ probeCenter[0].toFixed(4) }}, {{ probeCenter[1].toFixed(4) }}</code>
      </div>

      <!-- 半径切换 -->
      <div class="radius-selector">
        <span class="radius-label">检索半径:</span>
        <button
          v-for="r in [500, 1000, 2000, 5000]"
          :key="r"
          class="radius-btn"
          :class="{ active: probeRadius === r }"
          @click="changeRadius(r)"
        >
          {{ r >= 1000 ? `${r/1000}km` : `${r}m` }}
        </button>
      </div>

      <div v-if="probeLoading" class="stats-loading">正在计算空间距离与求交…</div>

      <template v-else>
        <div class="probe-section-title">🌲 周边绿地/林地 ({{ probeResults.length }})</div>
        <div v-if="probeResults.length === 0" class="stats-empty">半径 {{ probeRadius }}m 内无绿地</div>
        <div v-else class="probe-list">
          <div v-for="(item, idx) in probeResults.slice(0, 10)" :key="idx" class="probe-item">
            <div class="probe-item-header">
              <span class="cat-dot" :style="{ background: CATEGORY_COLOR[item.category] ?? '#888' }"></span>
              <span class="probe-name">{{ item.name }}</span>
              <span class="probe-dist">{{ item.distance_m }}m</span>
            </div>
            <div class="probe-item-detail">
              <span>{{ CATEGORY_LABEL[item.category] ?? item.category }}</span>
              <span>{{ item.area_ha.toFixed(2) }} 公顷</span>
            </div>
          </div>
        </div>

        <div v-if="probePois.length > 0" class="probe-section-title" style="margin-top:12px">
          📍 周边林业 POI ({{ probePois.length }})
        </div>
        <div v-if="probePois.length > 0" class="probe-list">
          <div v-for="(poi, idx) in probePois.slice(0, 6)" :key="idx" class="probe-item">
            <div class="probe-item-header">
              <span class="poi-dot" style="background:#00e676"></span>
              <span class="probe-name">{{ poi.name }}</span>
              <span class="probe-dist">{{ poi.distance_m }}m</span>
            </div>
          </div>
        </div>
      </template>
    </div>

  </div>
</template>

<style scoped>
/* ── 布局 ─────────────────────────────────────────────────── */
.page-wrapper {
  position: relative;
  width: 100%;
  height: 100vh;
}

.map-container {
  width: 100%;
  height: 100%;
}

/* ── 2D / 3D 视角切换按钮 ──────────────────────────────────── */
.view-mode-bar {
  position: absolute;
  top: 16px;
  left: 50%;
  transform: translateX(-50%);
  z-index: 10;
}
.view-btn {
  background: rgba(15, 20, 30, 0.88);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.15);
  color: #e2e8f0;
  padding: 8px 16px;
  border-radius: 20px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  box-shadow: 0 4px 20px rgba(0, 0, 0, 0.3);
  transition: all 0.3s ease;
}
.view-btn:hover {
  background: rgba(30, 41, 59, 0.95);
  border-color: #38bdf8;
  color: #38bdf8;
  transform: scale(1.03);
}
.view-btn.active {
  background: linear-gradient(135deg, #0284c7, #0369a1);
  border-color: #38bdf8;
  color: #ffffff;
  box-shadow: 0 0 16px rgba(56, 189, 248, 0.4);
}

.probe-btn {
  margin-left: 8px;
}
.probe-btn.active {
  background: linear-gradient(135deg, #0284c7, #0d9488);
  border-color: #38bdf8;
}

.bbox-btn {
  margin-left: 8px;
}
.bbox-btn.active {
  background: linear-gradient(135deg, #d97706, #b45309);
  border-color: #fbbf24;
  color: #ffffff;
  box-shadow: 0 0 16px rgba(251, 191, 36, 0.4);
}

.bbox-badge {
  margin-top: 6px;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
  padding: 4px 8px;
  border-radius: 20px;
  background: rgba(251, 191, 36, 0.15);
  color: #fbbf24;
  border: 1px solid rgba(251, 191, 36, 0.3);
}

/* ── 空间探针结果面板 ──────────────────────────────────────── */
.probe-panel {
  position: absolute;
  top: 70px;
  right: 16px;
  min-width: 280px;
  max-width: 320px;
  max-height: calc(100vh - 120px);
  overflow-y: auto;
  background: rgba(15, 20, 30, 0.92);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(56, 189, 248, 0.3);
  border-radius: 12px;
  padding: 14px 16px;
  color: #e2e8f0;
  font-size: 13px;
  box-shadow: 0 12px 36px rgba(0, 0, 0, 0.5);
  z-index: 10;
}

.probe-target-info {
  font-size: 11px;
  color: #94a3b8;
  margin-bottom: 10px;
}

.panel-close-btn {
  background: none;
  border: none;
  color: #94a3b8;
  font-size: 14px;
  cursor: pointer;
  padding: 0 4px;
  line-height: 1;
  transition: color 0.2s;
}
.panel-close-btn:hover {
  color: #ef4444;
}

.radius-selector {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 12px;
}
.radius-label {
  font-size: 11px;
  color: #64748b;
}
.radius-btn {
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.12);
  color: #cbd5e1;
  padding: 3px 8px;
  border-radius: 12px;
  font-size: 11px;
  cursor: pointer;
  transition: all 0.2s;
}
.radius-btn.active {
  background: #0284c7;
  color: #fff;
  border-color: #38bdf8;
}

.probe-section-title {
  font-size: 11px;
  font-weight: 600;
  color: #38bdf8;
  text-transform: uppercase;
  letter-spacing: 0.04em;
  margin-bottom: 6px;
}

.probe-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.probe-item {
  background: rgba(255, 255, 255, 0.03);
  border: 1px solid rgba(255, 255, 255, 0.06);
  border-radius: 6px;
  padding: 6px 8px;
}

.probe-item-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 6px;
}

.probe-name {
  font-weight: 500;
  color: #f1f5f9;
  flex: 1;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.probe-dist {
  font-size: 11px;
  font-weight: 600;
  color: #38bdf8;
}

.probe-item-detail {
  display: flex;
  justify-content: space-between;
  font-size: 11px;
  color: #64748b;
  margin-top: 3px;
}

/* ── 通用面板基础样式 ──────────────────────────────────────── */
.layer-panel,
.stats-panel {
  position: absolute;
  background: rgba(15, 20, 30, 0.88);
  backdrop-filter: blur(12px);
  border: 1px solid rgba(255, 255, 255, 0.10);
  border-radius: 12px;
  padding: 14px 16px;
  color: #e2e8f0;
  font-size: 13px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.4);
  z-index: 10;
}

.panel-title {
  font-size: 13px;
  font-weight: 600;
  color: #94a3b8;
  letter-spacing: 0.05em;
  text-transform: uppercase;
  margin-bottom: 10px;
  padding-bottom: 8px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}

/* ── 图层控制面板 ──────────────────────────────────────────── */
.layer-panel {
  top: 16px;
  left: 16px;
  min-width: 170px;
  max-height: calc(100vh - 32px);
  overflow-y: auto;
}

.layer-item {
  display: flex;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  padding: 5px 0;
  border-radius: 6px;
  transition: color 0.2s;
  user-select: none;
}
.layer-item.off {
  color: #64748b;
}
.layer-item input[type="checkbox"] {
  accent-color: #2da44e;
  cursor: pointer;
}
.layer-dot {
  width: 12px;
  height: 12px;
  border-radius: 3px;
  flex-shrink: 0;
}

.poi-legend {
  display: grid;
  grid-template-columns: 1fr 1fr 1fr;
  gap: 4px 2px;
  margin: 5px 0 8px;
  padding: 8px;
  background: rgba(255,255,255,0.04);
  border-radius: 6px;
}
.poi-legend-item {
  display: flex;
  align-items: center;
  gap: 5px;
  font-size: 11px;
  color: #94a3b8;
}
.poi-dot {
  width: 9px;
  height: 9px;
  border-radius: 50%;
  flex-shrink: 0;
  border: 1px solid rgba(255,255,255,0.3);
}

.choropleth-legend {
  margin: 6px 0 10px;
  padding: 6px 8px;
  background: rgba(255,255,255,0.04);
  border-radius: 6px;
}
.ramp-bar {
  height: 8px;
  border-radius: 4px;
  background: linear-gradient(to right, #f0fdf4, #bbf7d0, #4ade80, #16a34a, #14532d);
  margin: 4px 0 3px;
}
.ramp-labels {
  display: flex;
  justify-content: space-between;
  font-size: 10px;
  color: #94a3b8;
}

.legend-title {
  font-size: 11px;
  color: #64748b;
  margin: 12px 0 6px;
  text-transform: uppercase;
  letter-spacing: 0.05em;
}
.legend-item {
  display: flex;
  align-items: center;
  gap: 7px;
  padding: 3px 0;
  font-size: 12px;
}
.legend-color {
  width: 12px;
  height: 12px;
  border-radius: 2px;
  flex-shrink: 0;
}

.mode-badge {
  margin-top: 10px;
  font-size: 11px;
  font-weight: 600;
  text-align: center;
  padding: 4px 8px;
  border-radius: 20px;
  letter-spacing: 0.03em;
}
.mode-badge.tile {
  background: rgba(74, 222, 128, 0.15);
  color: #4ade80;
  border: 1px solid rgba(74, 222, 128, 0.3);
}
.mode-badge.geojson {
  background: rgba(100, 116, 139, 0.15);
  color: #94a3b8;
  border: 1px solid rgba(100, 116, 139, 0.25);
}

/* ── 统计面板 ──────────────────────────────────────────────── */
.stats-panel {
  bottom: 24px;
  right: 16px;
  left: auto;
  min-width: 240px;
  max-width: 280px;
}

.stats-loading {
  color: #64748b;
  text-align: center;
  padding: 8px 0;
}

.stats-summary {
  display: flex;
  gap: 10px;
  margin-bottom: 12px;
}
.stat-card {
  flex: 1;
  background: rgba(45, 164, 78, 0.12);
  border: 1px solid rgba(45, 164, 78, 0.25);
  border-radius: 8px;
  padding: 8px 10px;
  text-align: center;
}
.stat-value {
  font-size: 15px;
  font-weight: 700;
  color: #4ade80;
  line-height: 1.2;
}
.stat-label {
  font-size: 11px;
  color: #64748b;
  margin-top: 3px;
}

.stats-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.stats-table th {
  color: #64748b;
  font-weight: 500;
  text-align: left;
  padding: 4px 6px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
}
.stats-table td {
  padding: 5px 6px;
  color: #cbd5e1;
  vertical-align: middle;
}
.stats-table tr:hover td {
  background: rgba(255, 255, 255, 0.04);
  border-radius: 4px;
}

.cat-dot {
  display: inline-block;
  width: 8px;
  height: 8px;
  border-radius: 2px;
  margin-right: 5px;
  vertical-align: middle;
}

.stats-empty {
  text-align: center;
  color: #475569;
  padding: 12px 0;
  line-height: 1.8;
}

/* ── Popup 全局样式（注入 MapLibre DOM） ──────────────────── */
</style>

<!-- MapLibre popup 内部 DOM 不在 scoped 内，需 :global -->
<style>
.map-popup {
  font-family: system-ui, sans-serif;
  font-size: 13px;
  color: #1e293b;
}
.popup-title {
  font-weight: 600;
  font-size: 14px;
  margin-bottom: 8px;
  color: #0f172a;
  border-bottom: 1px solid #e2e8f0;
  padding-bottom: 6px;
}
.popup-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 12px;
}
.popup-table td {
  padding: 3px 6px;
  vertical-align: top;
}
.popup-table td:first-child {
  color: #64748b;
  white-space: nowrap;
  padding-right: 10px;
}
</style>
