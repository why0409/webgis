package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class BoundaryRepository {

    private final JdbcTemplate jdbcTemplate;

    public BoundaryRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<GeoJsonFeature> findByName(String name) {
        String sql = """
                SELECT name, id AS osm_id, ST_AsGeoJSON(geom) AS geojson
                FROM osm_boundaries
                WHERE name = ?
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name", rs.getString("name"));
            properties.put("osm_id", rs.getString("osm_id"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        }, name);
    }
}
