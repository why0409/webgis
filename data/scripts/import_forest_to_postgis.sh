#!/bin/bash
# 把 raw/shushan_forests.json 和 shushan_forest_pois.json 转换为 GeoJSON 并导入 PostGIS
# 依赖: osmtogeojson (npx自动拉取), ogr2ogr (brew install gdal), 运行中的 webgis-postgis 容器
set -e

cd "$(dirname "$0")/../raw"

PGCONN="PG:host=localhost port=5433 dbname=webgis user=webgis password=webgis123"

echo "==> 1/5 转换林地面数据 (osmtogeojson)"
npx --yes osmtogeojson shushan_forests.json > shushan_forests.geojson
echo "   已生成 shushan_forests.geojson"

echo "==> 2/5 过滤：只保留面/多面要素（剔除osmtogeojson产生的Point质心要素）"
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_forests.geojson'))
original = len(d['features'])
d['features'] = [f for f in d['features'] if f['geometry']['type'] in ('Polygon', 'MultiPolygon')]

# 补充 category 字段，方便前端分色渲染
for f in d['features']:
    p = f['properties'] or {}
    landuse  = p.get('landuse',  '')
    leisure  = p.get('leisure',  '')
    natural_ = p.get('natural',  '')

    if landuse in ('forest',):
        cat = 'forest'
    elif natural_ in ('wood', 'scrub', 'heath'):
        cat = 'wood'
    elif landuse in ('grass', 'meadow', 'village_green', 'recreation_ground') or natural_ in ('grassland',):
        cat = 'grass'
    elif leisure in ('park', 'garden', 'nature_reserve'):
        cat = 'park'
    else:
        cat = 'other'
    f['properties']['category'] = cat

json.dump(d, open('shushan_forests_filtered.geojson', 'w'), ensure_ascii=False)
print(f"  原始 {original} 个要素 → 保留面要素 {len(d['features'])} 个")

from collections import Counter
cats = Counter(f['properties']['category'] for f in d['features'])
for k, v in cats.most_common():
    print(f"    {k}: {v}")
PYEOF

echo "==> 3/5 转换林业 POI 节点 (osmtogeojson)"
npx --yes osmtogeojson shushan_forest_pois.json > shushan_forest_pois.geojson
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_forest_pois.geojson'))
for f in d['features']:
    p = f.setdefault('properties', {})
    p.setdefault('landuse', None)
    p.setdefault('leisure', None)
    p.setdefault('natural', None)
json.dump(d, open('shushan_forest_pois.geojson', 'w'), ensure_ascii=False)
PYEOF
echo "   已生成并规范化 shushan_forest_pois.geojson"

echo "==> 4/5 导入 PostGIS"
echo "   → osm_forests (MULTIPOLYGON)"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_forests_filtered.geojson \
  -nln osm_forests -nlt MULTIPOLYGON -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "   → osm_forest_pois (POINT)"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_forest_pois.geojson \
  -nln osm_forest_pois -nlt POINT -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "==> 5/5 验证导入结果"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT category,
         count(*) AS cnt,
         round(sum(ST_Area(geom::geography))::numeric/10000, 2) AS area_ha
   FROM osm_forests GROUP BY category ORDER BY area_ha DESC;"

docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT count(*) AS forest_poi_count FROM osm_forest_pois;"

echo "==> 完成！"
