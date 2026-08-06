package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 3D 建筑物 Footprint 与高度数据仓库 (Stage 4)
 * 查询 osm_buildings 表，输出 GeoJSON 几何与 height / min_height 属性
 */
@Repository
public class BuildingRepository {

    private final JdbcTemplate jdbcTemplate;

    public BuildingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GeoJsonFeature> findAll() {
        String sql = """
                SELECT name, building, height, min_height,
                       id AS osm_id,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_buildings
                WHERE geom IS NOT NULL
                LIMIT 5000
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",       rs.getString("name"));
            properties.put("building",   rs.getString("building"));
            properties.put("height",     rs.getDouble("height"));
            properties.put("min_height", rs.getDouble("min_height"));
            properties.put("osm_id",     rs.getString("osm_id"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        });
    }
}
