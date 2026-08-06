package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.BuildingRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BuildingController {

    private final BuildingRepository buildingRepository;

    public BuildingController(BuildingRepository buildingRepository) {
        this.buildingRepository = buildingRepository;
    }

    /**
     * GET /api/buildings
     * 返回包含建筑物高度 (height) 与底座高度 (min_height) 的 GeoJSON FeatureCollection
     */
    @GetMapping("/api/buildings")
    public GeoJsonFeatureCollection getBuildings() {
        return new GeoJsonFeatureCollection(buildingRepository.findAll());
    }
}
