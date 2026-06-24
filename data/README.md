# 数据获取与导入

数据范围：安徽省合肥市蜀山区 (OSM relation id: [3288963](https://www.openstreetmap.org/relation/3288963))

## 1. 拉取原始数据

```bash
./scripts/fetch_osm_shushan.sh
```

会在 `raw/` 下生成：
- `shushan_boundary.json` — 蜀山区行政边界（Overpass JSON, relation+geom）
- `shushan_pois.json` — 蜀山区范围内 POI（amenity 点）
- `shushan_buildings.json` — 建筑物 footprint（留给后续 3D 阶段用，Overpass 公共接口有限流，如遇 rate_limited 报错请稍后重试）

`raw/` 目录已加入 `.gitignore`，不提交进版本库。

## 2. 导入 PostGIS

```bash
./scripts/import_to_postgis.sh
```

会创建 `osm_boundaries` 和 `osm_pois` 两个表。导入后建议验证 SRID：

```bash
docker exec -it webgis-postgis psql -U webgis -d webgis -c \
  "SELECT name, ST_GeometryType(geom), ST_SRID(geom) FROM osm_boundaries;"
```
