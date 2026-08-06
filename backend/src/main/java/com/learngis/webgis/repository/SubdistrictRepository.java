package com.learngis.webgis.repository;

import com.learngis.webgis.dto.GeoJsonFeature;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 街道/乡镇行政区划与绿化覆盖率空间分析仓库 (Stage 3: Choropleth Styling)
 * 使用 PostGIS ST_Intersection 空间重叠求交，计算各街道绿地交集面积与覆盖率 (%)
 */
@Repository
public class SubdistrictRepository {

    private final JdbcTemplate jdbcTemplate;

    public SubdistrictRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /**
     * 查询各街道边界及动态空间计算得到的绿化覆盖率数据，返回 GeoJSON Feature 列表
     */
    public List<GeoJsonFeature> findChoropleth() {
        String sql = """
                SELECT s.name AS name,
                       s.id AS osm_id,
                       round((ST_Area(s.geom::geography)/10000)::numeric, 2) AS total_area_ha,
                       round((coalesce(sum(ST_Area(ST_Intersection(s.geom, f.geom)::geography)), 0)/10000)::numeric, 2) AS green_area_ha,
                       round((coalesce(sum(ST_Area(ST_Intersection(s.geom, f.geom)::geography)), 0) / NULLIF(ST_Area(s.geom::geography), 0) * 100)::numeric, 2) AS green_rate_pct,
                       ST_AsGeoJSON(s.geom) AS geojson
                FROM osm_subdistricts s
                LEFT JOIN osm_forests f ON ST_Intersects(s.geom, f.geom)
                WHERE s.geom IS NOT NULL
                GROUP BY s.ogc_fid, s.name, s.id, s.geom
                ORDER BY green_rate_pct DESC
                """;

        return jdbcTemplate.query(sql, (rs, rowNum) -> {
            Map<String, Object> properties = new LinkedHashMap<>();
            double greenRate = rs.getDouble("green_rate_pct");
            properties.put("name",            rs.getString("name"));
            properties.put("osm_id",          rs.getString("osm_id"));
            properties.put("total_area_ha",   rs.getDouble("total_area_ha"));
            properties.put("green_area_ha",   rs.getDouble("green_area_ha"));
            properties.put("green_rate_pct",  greenRate);

            String grade;
            if (greenRate >= 35.0) {
                grade = "High";
            } else if (greenRate >= 20.0) {
                grade = "Medium";
            } else {
                grade = "Low";
            }
            properties.put("green_grade", grade);

            return new GeoJsonFeature(properties, rs.getString("geojson"));
        });
    }
}
