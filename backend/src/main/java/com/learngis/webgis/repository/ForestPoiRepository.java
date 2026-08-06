package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 林业相关 POI 数据仓库（表：osm_forest_pois）
 * 包含苗圃、植物园、自然保护区、公园节点、树木等
 */
@Repository
public class ForestPoiRepository {

    private final JdbcTemplate jdbcTemplate;

    public ForestPoiRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GeoJsonFeature> findAll() {
        String sql = """
                SELECT name,
                       coalesce(landuse, leisure, "natural") AS poi_type,
                       landuse, leisure, "natural",
                       id AS osm_id,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_forest_pois
                WHERE geom IS NOT NULL
                LIMIT 2000
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",     rs.getString("name"));
            properties.put("poi_type", rs.getString("poi_type"));
            properties.put("landuse",  rs.getString("landuse"));
            properties.put("leisure",  rs.getString("leisure"));
            properties.put("natural",  rs.getString("natural"));
            properties.put("osm_id",   rs.getString("osm_id"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        });
    }
}
