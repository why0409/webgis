package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.ForestPoiRepository;
import com.learngis.webgis.repository.ForestRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
public class ForestController {

    private final ForestRepository forestRepository;
    private final ForestPoiRepository forestPoiRepository;

    public ForestController(ForestRepository forestRepository,
                            ForestPoiRepository forestPoiRepository) {
        this.forestRepository    = forestRepository;
        this.forestPoiRepository = forestPoiRepository;
    }

    /**
     * GET /api/forests
     * 可选参数 category=forest|wood|grass|park|other，不传则返回全部
     */
    @GetMapping("/api/forests")
    public GeoJsonFeatureCollection getForests(
            @RequestParam(required = false) String category) {
        if (category != null && !category.isBlank()) {
            return new GeoJsonFeatureCollection(forestRepository.findByCategory(category));
        }
        return new GeoJsonFeatureCollection(forestRepository.findAll());
    }

    /**
     * GET /api/forests/stats
     * 返回各分类面积/数量统计，用于前端统计面板
     * 格式：[{"category":"park","count":12,"area_ha":45.3}, ...]
     */
    @GetMapping("/api/forests/stats")
    public List<Map<String, Object>> getForestStats() {
        return forestRepository.getStats();
    }

    /**
     * GET /api/forests/pois
     * 林业相关 POI 点位（苗圃、植物园、自然保护区、树木等）
     */
    @GetMapping("/api/forests/pois")
    public GeoJsonFeatureCollection getForestPois() {
        return new GeoJsonFeatureCollection(forestPoiRepository.findAll());
    }

    /**
     * Stage 6: GET /api/forests/bbox?minLng=...&minLat=...&maxLng=...&maxLat=...
     * 视口 BBOX 按需范围空间检索
     */
    @GetMapping("/api/forests/bbox")
    public GeoJsonFeatureCollection getForestsByBbox(
            @RequestParam double minLng,
            @RequestParam double minLat,
            @RequestParam double maxLng,
            @RequestParam double maxLat) {
        return new GeoJsonFeatureCollection(forestRepository.findByBbox(minLng, minLat, maxLng, maxLat));
    }
}
