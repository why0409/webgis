#!/bin/bash
# 把 raw/ 下的 Overpass JSON 转换为 GeoJSON 并导入 PostGIS
# 依赖: osmtogeojson (npx自动拉取), ogr2ogr (brew install gdal), 运行中的 webgis-postgis 容器
set -e

cd "$(dirname "$0")/../raw"

PGCONN="PG:host=localhost port=5433 dbname=webgis user=webgis password=webgis123"

echo "==> 1/4 转换 Overpass JSON 为标准 GeoJSON (osmtogeojson)"
npx --yes osmtogeojson shushan_boundary.json > shushan_boundary.geojson
npx --yes osmtogeojson shushan_pois.json > shushan_pois.geojson

echo "==> 2/4 过滤边界数据：Overpass relation 转换后混有非面要素(如行政中心点)，只保留面"
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_boundary.geojson'))
d['features'] = [f for f in d['features'] if f['geometry']['type'] in ('Polygon', 'MultiPolygon')]
json.dump(d, open('shushan_boundary_filtered.geojson', 'w'))
print(f"  保留 {len(d['features'])} 个面要素")
PYEOF

echo "==> 3/4 导入蜀山区边界 -> osm_boundaries"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_boundary_filtered.geojson \
  -nln osm_boundaries -nlt MULTIPOLYGON -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "==> 3/4 导入 POI -> osm_pois"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_pois.geojson \
  -nln osm_pois -nlt POINT -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "==> 4/4 验证导入结果"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT name, ST_GeometryType(geom), ST_SRID(geom) FROM osm_boundaries;"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT count(*) AS poi_count FROM osm_pois;"
