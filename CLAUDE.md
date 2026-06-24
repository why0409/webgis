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

### Frontend (`frontend/`)

Single page, no router/Pinia (intentionally — add them only when the app actually grows past one view). `src/api/gisApi.ts` wraps the two backend endpoints; `src/components/MapView.vue` owns the MapLibre instance and adds GeoJSON sources/layers on `map.on('load')`. Base style is the public `https://demotiles.maplibre.org/style.json` (no API key needed) — swap this out deliberately if upgrading the basemap, not as a side effect of another change.

## Commands

```bash
# Database (run once per session, or whenever it's not already up)
cd docker && docker compose up -d

# Data: fetch + import (only needed when re-pulling/changing the OSM dataset)
data/scripts/fetch_osm_shushan.sh      # Overpass API -> data/raw/*.json
data/scripts/import_to_postgis.sh      # osmtogeojson + ogr2ogr -> PostGIS tables

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

## Roadmap (not yet implemented)

Stage 2: vector tiles (pg_tileserv/Martin). Stage 3: choropleth styling. Stage 4: Cesium 3D building extrusion (building footprints are already fetched into `data/raw/shushan_buildings.json` but not yet imported — Overpass rate-limits this query, re-fetch may be needed). Stage 5: interactive spatial queries (`ST_DWithin`), this is where JTS gets introduced on the backend. Stage 6: performance (GIST indexes, viewport-based bbox loading).
