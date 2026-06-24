package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class PoiRepository {

    private final JdbcTemplate jdbcTemplate;

    public PoiRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    // TODO: category 目前不参与过滤，后续阶段做属性+空间联合查询时在这里加 WHERE amenity = ?
    public List<GeoJsonFeature> findAll() {
        String sql = """
                SELECT name, amenity, id AS osm_id, ST_AsGeoJSON(geom) AS geojson
                FROM osm_pois
                WHERE geom IS NOT NULL
                LIMIT 2000
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name", rs.getString("name"));
            properties.put("amenity", rs.getString("amenity"));
            properties.put("osm_id", rs.getString("osm_id"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        });
    }
}
