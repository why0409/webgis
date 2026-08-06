#!/bin/bash
# 将 raw/shushan_buildings.json 转换为 GeoJSON 并导入 PostGIS 表 osm_buildings
set -e

cd "$(dirname "$0")/../raw"

PGCONN="PG:host=localhost port=5433 dbname=webgis user=webgis password=webgis123"

echo "==> 1/4 转换建筑物 Overpass JSON 为 GeoJSON (osmtogeojson)"
npx --yes osmtogeojson shushan_buildings.json > shushan_buildings.geojson

echo "==> 2/4 过滤面要素并计算高度 (height / building:levels)"
python3 - <<'PYEOF'
import json
d = json.load(open('shushan_buildings.geojson'))
original = len(d['features'])
d['features'] = [f for f in d['features'] if f['geometry']['type'] in ('Polygon', 'MultiPolygon')]

# 规范化建筑物高度 (height) 与 基础高度 (min_height)
for f in d['features']:
    p = f.setdefault('properties', {})
    
    # 提取 height 或根据 building:levels 计算
    h = p.get('height')
    levels = p.get('building:levels')
    min_h = p.get('min_height', 0)
    
    parsed_height = 15.0 # 默认 15 米 (约 5 层楼)
    if h:
        try:
            parsed_height = float(str(h).replace('m', '').strip())
        except ValueError:
            pass
    elif levels:
        try:
            parsed_height = float(levels) * 3.5
        except ValueError:
            pass
            
    p['height'] = round(parsed_height, 1)
    try:
        p['min_height'] = float(str(min_h).replace('m', '').strip())
    except ValueError:
        p['min_height'] = 0.0

json.dump(d, open('shushan_buildings_filtered.geojson', 'w'), ensure_ascii=False)
print(f"  原始 {original} 要素 → 保留面要素 {len(d['features'])} 个")
PYEOF

echo "==> 3/4 导入 PostGIS -> osm_buildings"
ogr2ogr -f PostgreSQL "$PGCONN" shushan_buildings_filtered.geojson \
  -nln osm_buildings -nlt MULTIPOLYGON -t_srs EPSG:4326 -overwrite \
  -lco GEOMETRY_NAME=geom

echo "==> 4/4 创建 GIST 空间索引"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "CREATE INDEX IF NOT EXISTS idx_osm_buildings_geom ON osm_buildings USING GIST(geom);"

echo "==> 5/5 验证导入结果"
docker exec webgis-postgis psql -U webgis -d webgis -c \
  "SELECT count(*) AS building_count, round(avg(height::numeric), 1) AS avg_height_m FROM osm_buildings;"

echo "==> 完成！"
