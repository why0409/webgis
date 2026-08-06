package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.locationtech.jts.geom.Coordinate;
import org.locationtech.jts.geom.GeometryFactory;
import org.locationtech.jts.geom.Point;
import org.locationtech.jts.geom.PrecisionModel;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 交互式空间分析仓库 (Stage 5: Spatial Probe & Buffer ST_DWithin Queries)
 * 结合 JTS (Java Topology Suite) 构造参数点，并运用 PostGIS ST_DWithin & ST_Distance 进行空间距离与缓冲区检索
 */
@Repository
public class SpatialQueryRepository {

    private final JdbcTemplate jdbcTemplate;
    private final GeometryFactory geometryFactory = new GeometryFactory(new PrecisionModel(), 4326);

    public SpatialQueryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 根据给定经纬度坐标 (lng, lat) 和搜索半径 (radiusMeters)，
     * 使用 JTS 构造目标 Geometry Point，并在 PostGIS 中执行 ST_DWithin 检索附近的林地/绿地要素
     */
    public List<GeoJsonFeature> findNearbyForests(double lng, double lat, double radiusMeters) {
        Point centerPoint = geometryFactory.createPoint(new Coordinate(lng, lat));
        double targetLng = centerPoint.getX();
        double targetLat = centerPoint.getY();

        String sql = """
                SELECT name, category, landuse, leisure, "natural",
                       id AS osm_id,
                       round((ST_Area(geom::geography)/10000)::numeric, 2) AS area_ha,
                       round(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)::numeric, 1) AS distance_m,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_forests
                WHERE geom IS NOT NULL
                  AND ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ORDER BY distance_m ASC
                LIMIT 50
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",       rs.getString("name"));
            properties.put("category",   rs.getString("category"));
            properties.put("landuse",    rs.getString("landuse"));
            properties.put("leisure",    rs.getString("leisure"));
            properties.put("natural",    rs.getString("natural"));
            properties.put("osm_id",     rs.getString("osm_id"));
            properties.put("area_ha",    rs.getDouble("area_ha"));
            properties.put("distance_m", rs.getDouble("distance_m"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        }, targetLng, targetLat, targetLng, targetLat, radiusMeters);
    }

    /**
     * 检索指定点周围半径内附近的林业 POI 点位
     */
    public List<GeoJsonFeature> findNearbyForestPois(double lng, double lat, double radiusMeters) {
        Point centerPoint = geometryFactory.createPoint(new Coordinate(lng, lat));
        double targetLng = centerPoint.getX();
        double targetLat = centerPoint.getY();

        String sql = """
                SELECT name,
                       coalesce(landuse, leisure, "natural") AS poi_type,
                       id AS osm_id,
                       round(ST_Distance(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography)::numeric, 1) AS distance_m,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_forest_pois
                WHERE geom IS NOT NULL
                  AND ST_DWithin(geom::geography, ST_SetSRID(ST_MakePoint(?, ?), 4326)::geography, ?)
                ORDER BY distance_m ASC
                LIMIT 50
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",       rs.getString("name"));
            properties.put("poi_type",   rs.getString("poi_type"));
            properties.put("osm_id",     rs.getString("osm_id"));
            properties.put("distance_m", rs.getDouble("distance_m"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        }, targetLng, targetLat, targetLng, targetLat, radiusMeters);
    }
}
