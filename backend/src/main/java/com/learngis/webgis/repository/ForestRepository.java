package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 林地/绿地面数据仓库（表：osm_forests）
 * category 字段由导入脚本在 Python 端分类写入：
 *   forest / wood / grass / park / other
 */
@Repository
public class ForestRepository {

    private final JdbcTemplate jdbcTemplate;

    public ForestRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** 查询所有林地面要素，返回 GeoJSON Feature 列表 */
    public List<GeoJsonFeature> findAll() {
        String sql = """
                SELECT name, category, landuse, leisure, "natural",
                       id AS osm_id,
                       round(ST_Area(geom::geography)::numeric, 2) AS area_m2,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_forests
                WHERE geom IS NOT NULL
                ORDER BY ST_Area(geom) DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",     rs.getString("name"));
            properties.put("category", rs.getString("category"));
            properties.put("landuse",  rs.getString("landuse"));
            properties.put("leisure",  rs.getString("leisure"));
            properties.put("natural",  rs.getString("natural"));
            properties.put("osm_id",   rs.getString("osm_id"));
            properties.put("area_m2",  rs.getDouble("area_m2"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        });
    }

    /** 按分类过滤（category = forest/wood/grass/park） */
    public List<GeoJsonFeature> findByCategory(String category) {
        String sql = """
                SELECT name, category, landuse, leisure, "natural",
                       id AS osm_id,
                       round(ST_Area(geom::geography)::numeric, 2) AS area_m2,
                       ST_AsGeoJSON(geom) AS geojson
                FROM osm_forests
                WHERE geom IS NOT NULL
                  AND category = ?
                ORDER BY ST_Area(geom) DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            properties.put("name",     rs.getString("name"));
            properties.put("category", rs.getString("category"));
            properties.put("landuse",  rs.getString("landuse"));
            properties.put("leisure",  rs.getString("leisure"));
            properties.put("natural",  rs.getString("natural"));
            properties.put("osm_id",   rs.getString("osm_id"));
            properties.put("area_m2",  rs.getDouble("area_m2"));
            return new GeoJsonFeature(properties, rs.getString("geojson"));
        }, category);
    }

    /**
     * 返回各分类的面积统计（单位：公顷），用于前端统计面板
     * 返回 List<Map>，每项包含 category / count / area_ha
     */
    public List<Map<String, Object>> getStats() {
        String sql = """
                SELECT category,
                       count(*) AS cnt,
                       round(sum(ST_Area(geom::geography))::numeric / 10000, 2) AS area_ha
                FROM osm_forests
                WHERE geom IS NOT NULL
                GROUP BY category
                ORDER BY area_ha DESC
                """;
        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("category", rs.getString("category"));
            row.put("count",    rs.getLong("cnt"));
            row.put("area_ha",  rs.getDouble("area_ha"));
            return row;
        });
    }
}
