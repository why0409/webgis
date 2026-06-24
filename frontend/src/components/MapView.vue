<script setup lang="ts">
import { onMounted, ref } from 'vue'
import maplibregl from 'maplibre-gl'
import 'maplibre-gl/dist/maplibre-gl.css'
import { fetchShushanBoundary, fetchPois } from '../api/gisApi'

const mapContainer = ref<HTMLDivElement>()

onMounted(() => {
  const map = new maplibregl.Map({
    container: mapContainer.value!,
    style: 'https://demotiles.maplibre.org/style.json',
    center: [117.27, 31.85],
    zoom: 11
  })

  map.on('load', async () => {
    const boundary = await fetchShushanBoundary()
    map.addSource('shushan-boundary', { type: 'geojson', data: boundary })
    map.addLayer({
      id: 'shushan-boundary-fill',
      type: 'fill',
      source: 'shushan-boundary',
      paint: { 'fill-color': '#ff6600', 'fill-opacity': 0.08 }
    })
    map.addLayer({
      id: 'shushan-boundary-line',
      type: 'line',
      source: 'shushan-boundary',
      paint: { 'line-color': '#ff6600', 'line-width': 2 }
    })

    const pois = await fetchPois()
    map.addSource('pois', { type: 'geojson', data: pois })
    map.addLayer({
      id: 'pois-circle',
      type: 'circle',
      source: 'pois',
      paint: {
        'circle-radius': 4,
        'circle-color': '#2266ff',
        'circle-stroke-width': 1,
        'circle-stroke-color': '#ffffff'
      }
    })
  })
})
</script>

<template>
  <div ref="mapContainer" style="width: 100%; height: 100vh"></div>
</template>
