#!/bin/bash
# 拉取合肥市蜀山区的 OSM 原始数据：行政边界(relation) + POI(amenity点) + 建筑物footprint
# 蜀山区 OSM relation id: 3288963 (https://www.openstreetmap.org/relation/3288963)
set -e

cd "$(dirname "$0")/../raw"

SHUSHAN_RELATION_ID=3288963
# 蜀山区大致bbox (south,west,north,east)，WGS84经纬度
BBOX="31.74,117.10,31.95,117.28"

echo "==> 1/3 拉取蜀山区行政边界 (relation $SHUSHAN_RELATION_ID)"
curl -s "https://overpass-api.de/api/interpreter" \
  --data-urlencode "data=[out:json][timeout:60];relation($SHUSHAN_RELATION_ID);out geom;" \
  -o shushan_boundary.json

echo "==> 2/3 拉取蜀山区范围内的 POI (amenity)"
curl -s "https://overpass-api.de/api/interpreter" \
  --data-urlencode "data=[out:json][timeout:120];node[\"amenity\"]($BBOX);out body;" \
  -o shushan_pois.json

echo "==> 3/3 拉取蜀山区范围内的建筑物 footprint (本阶段先存档，3D阶段再用)"
curl -s "https://overpass-api.de/api/interpreter" \
  --data-urlencode "data=[out:json][timeout:120];way[\"building\"]($BBOX);out geom;" \
  -o shushan_buildings.json

echo "==> 完成，文件位于 data/raw/"
ls -lh shushan_boundary.json shushan_pois.json shushan_buildings.json
