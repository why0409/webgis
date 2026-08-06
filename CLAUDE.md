# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

A WebGIS learning project. The goal is to learn GIS concepts (coordinate systems, GeoJSON, PostGIS spatial queries, 2D/3D map rendering) by building a real visualization of OSM data for Shushan District, Hefei (合肥市蜀山区, OSM relation id `3288963`). The author knows Java web dev and Vue, but is new to GIS — code should stay simple and transparent rather than hidden behind heavy abstractions (see backend notes below).

The project is built incrementally in stages; see "Roadmap" below. Don't jump ahead of the current stage without being asked.

## Architecture

Four independent pieces, no monorepo tooling:

```
docker/    PostgreSQL+PostGIS via docker-compose, port 5433 on host
data/      one-off scripts to fetch OSM data and import into PostGIS
backend/   Spring Boot REST API, returns GeoJSON
frontend/  Vue3 + Vite + MapLibre GL JS, renders the GeoJSON
```

Data flow: Overpass API → `osmtogeojson` (npx) → `ogr2ogr` → PostGIS → Spring Boot (`ST_AsGeoJSON`) → Vue/MapLibre.

### Backend (`backend/`)

Spring Boot 3.3, Java 17, package `com.learngis.webgis`. Deliberately uses `JdbcTemplate` + raw spatial SQL instead of Hibernate Spatial/JPA/JTS — this is intentional, not a shortcut to fix later: the point of the project is to see the `ST_AsGeoJSON` / spatial SQL directly rather than have an ORM hide it. Keep this pattern when adding endpoints unless the task specifically calls for JTS (e.g. binding a client-supplied point into `ST_DWithin`).

`GeoJsonFeature.geometry` is annotated `@JsonRawValue` and assigned directly from the `ST_AsGeoJSON(geom)` column — the DB produces the GeoJSON geometry string, Java never deserializes it into a geometry object. Controllers are thin; repositories hold the SQL.

CORS is restricted to `http://localhost:5173` in `config/CorsConfig.java`.

### Database tables

`osm_boundaries` (MultiPolygon, admin boundaries) and `osm_pois` (Point, POIs with many sparse OSM tag columns) — both created by `ogr2ogr` from GeoJSON, geometry column named `geom` (not the ogr2ogr default `wkb_geometry` — imports explicitly pass `-lco GEOMETRY_NAME=geom`), SRID 4326. The OSM id is in a column literally named `id` (e.g. `"node/123"`, `"relation/456"`), not `osm_id`.

`osm_forests` (MultiPolygon, 林地/绿地面) — created by `import_forest_to_postgis.sh`; includes a `category` column (forest/wood/grass/park/other) added by the Python filter step. `osm_forest_pois` (Point, 林业相关节点) — nurseries, botanical gardens, trees, etc.

### Frontend (`frontend/`)

Single page, no router/Pinia (intentionally — add them only when the app actually grows past one view). `src/api/gisApi.ts` wraps backend endpoints; `src/components/MapView.vue` owns the MapLibre instance and adds GeoJSON sources/layers on `map.on('load')`. Base style is the public `https://demotiles.maplibre.org/style.json` (no API key needed) — swap this out deliberately if upgrading the basemap, not as a side effect of another change.

Forestry additions: `forests-fill`/`forests-line` layers render `osm_forests` polygons with a `match` expression keyed on `category`. `forest-pois-circle` renders `osm_forest_pois`. Both have click Popups. A layer-toggle panel (top-left) and a statistics panel (bottom-left) are rendered as `position:absolute` overlays in the Vue template.

## Commands

```bash
# Database (run once per session, or whenever it's not already up)
cd docker && docker compose up -d

# Data: fetch + import (only needed when re-pulling/changing the OSM dataset)
data/scripts/fetch_osm_shushan.sh      # Overpass API -> data/raw/*.json (boundary + amenity POI + buildings)
data/scripts/import_to_postgis.sh      # osmtogeojson + ogr2ogr -> osm_boundaries / osm_pois

# 林业数据（新增）
data/scripts/fetch_forest_data.sh      # 抓取林地面 + 林业POI -> data/raw/shushan_forests*.json
data/scripts/import_forest_to_postgis.sh  # 导入 -> osm_forests / osm_forest_pois

# Backend
cd backend && mvn spring-boot:run      # serves on :8080
cd backend && mvn -q -DskipTests package

# Frontend
cd frontend && npm install
cd frontend && npm run dev             # serves on :5173
cd frontend && npm run build           # vue-tsc -b && vite build
```

Full local run order: `docker compose up -d` → backend → frontend.

Verify the backend independent of the UI:
```bash
curl http://localhost:8080/api/boundaries/shushan
curl http://localhost:8080/api/pois
```

Verify data landed correctly (check SRID=4326, this is the most common import mistake):
```bash
docker exec -it webgis-postgis psql -U webgis -d webgis -c \
  "SELECT name, ST_GeometryType(geom), ST_SRID(geom) FROM osm_boundaries;"
```

## Roadmap

**Stage 2 ✅ 向量瓦片（Martin）** — `docker/docker-compose.yml` 新增 `martin` 服务（port 3000）。`frontend/src/composables/useVectorTiles.ts` 在 Martin 在线时自动将 `forests`/`forest-pois` 图层切换为 vector source，离线时静默保持 GeoJSON fallback。图层控制面板底部有"⚡ 向量瓦片模式 / 📦 GeoJSON 模式"badge 显示当前状态。Martin catalog: `http://localhost:3000/catalog`，瓦片: `http://localhost:3000/{table}/{z}/{x}/{y}`。

**Stage 3 ✅ 街道绿化率空间分析与分级专题图 (Choropleth Styling)** — 新增数据脚本 `fetch_subdistricts.sh` 抓取蜀山区 24 个街道/镇边界 (`osm_subdistricts`)。在 PostGIS 中使用 `ST_Intersection(s.geom, f.geom)` 进行空间求交和面积计算，得出各街道的绿地交集面积和绿化覆盖率 (%)。后端提供 `/api/subdistricts/choropleth`，前端渲染渐变分级专题图层、色带图例以及街道空间分析 Popup。

**Stage 4 ✅ 3D 建筑物白模拉伸与三维视图拓展 (3D Building Extrusion)** — 新增数据脚本 `fetch_buildings.sh` 抓取蜀山区 4,674 个建筑物 Footprint (`osm_buildings`)。导入脚本自动解析建筑物层数 `building:levels` / 高度 `height`，并在 PostGIS 中建立空间索引。Martin 自动提供矢量瓦片 `osm_buildings`，MapLibre 前端渲染 `fill-extrusion` 三维白模图层；顶部增加 `🏙️ 3D 俯瞰视角 / 🗺️ 2D 正投视角` 相机倾角 (pitch=60°) 动画切换按钮。

Stage 5: interactive spatial queries (`ST_DWithin`), this is where JTS gets introduced on the backend. Stage 6: performance (GIST indexes, viewport-based bbox loading).

