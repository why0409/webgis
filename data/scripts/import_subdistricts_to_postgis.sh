#!/bin/bash
# 将 raw/shushan_subdistricts.json 转换为 GeoJSON 并导入 PostGIS 表 osm_subdistricts
set -e

cd "$(dirname "$0")/../raw"

PGCONN="PG:host=localhost port=5433 dbname=webgis user=webgis password=webgis123"

echo "==> 1/4 转换街道 Overpass JSON 为 GeoJSON (osmtogeojson)"
npx --yes osmtogeojson shushan_subdistricts.json > shushan_subdistricts.geojson

echo "==> 2/4 过滤面要素并清理属性"
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_subdistricts.geojson'))
original = len(d['features'])
d['features'] = [f for f in d['features'] if f['geometry']['type'] in ('Polygon', 'MultiPolygon')]

# 清理名称
for f in d['features']:
    p = f.setdefault('properties', {})
    name = p.get('name', '未命名街道')
    p['name'] = name

json.dump(d, open('shushan_subdistricts_filtered.geojson', 'w'), ensure_ascii=False)
print(f"  原始 {original} 要素 → 保留面要素 {len(d['features'])} 个")
PYEOF

echo "==> 3/4 导入 PostGIS -> osm_subdistricts"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_subdistricts_filtered.geojson \
  -nln osm_subdistricts -nlt MULTIPOLYGON -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "==> 4/4 创建 GIST 索引"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "CREATE INDEX IF NOT EXISTS idx_osm_subdistricts_geom ON osm_subdistricts USING GIST(geom);"

echo "==> 5/5 验证街道空间重叠分析（计算各街道绿化覆盖率）"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT s.name AS subdistrict,
          round((ST_Area(s.geom::geography)/10000)::numeric, 2) AS total_area_ha,
          round((coalesce(sum(ST_Area(ST_Intersection(s.geom, f.geom)::geography)), 0)/10000)::numeric, 2) AS green_area_ha,
          round((coalesce(sum(ST_Area(ST_Intersection(s.geom, f.geom)::geography)), 0) / NULLIF(ST_Area(s.geom::geography), 0) * 100)::numeric, 2) AS green_rate_pct
   FROM osm_subdistricts s
   LEFT JOIN osm_forests f ON ST_Intersects(s.geom, f.geom)
   GROUP BY s.ogc_fid, s.name, s.geom
   ORDER BY green_rate_pct DESC;"

echo "==> 导入与分析完成！"
