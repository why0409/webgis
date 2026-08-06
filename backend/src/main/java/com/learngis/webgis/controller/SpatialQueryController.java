package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.SpatialQueryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SpatialQueryController {

    private final SpatialQueryRepository spatialQueryRepository;

    public SpatialQueryController(SpatialQueryRepository spatialQueryRepository) {
        this.spatialQueryRepository = spatialQueryRepository;
    }

    /**
     * GET /api/spatial/nearby/forests
     * 空间缓冲区探针接口：检索指定坐标点 (lng, lat) 给定半径 (radius，单位：米) 内的林地/绿地要素
     */
    @GetMapping("/api/spatial/nearby/forests")
    public GeoJsonFeatureCollection getNearbyForests(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(defaultValue = "1000") double radius) {
        return new GeoJsonFeatureCollection(spatialQueryRepository.findNearbyForests(lng, lat, radius));
    }

    /**
     * GET /api/spatial/nearby/pois
     * 检索指定坐标点周围给定半径内的林业 POI 点位
     */
    @GetMapping("/api/spatial/nearby/pois")
    public GeoJsonFeatureCollection getNearbyForestPois(
            @RequestParam double lng,
            @RequestParam double lat,
            @RequestParam(defaultValue = "1000") double radius) {
        return new GeoJsonFeatureCollection(spatialQueryRepository.findNearbyForestPois(lng, lat, radius));
    }
}
