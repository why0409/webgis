package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.SubdistrictRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SubdistrictController {

    private final SubdistrictRepository subdistrictRepository;

    public SubdistrictController(SubdistrictRepository subdistrictRepository) {
        this.subdistrictRepository = subdistrictRepository;
    }

    /**
     * GET /api/subdistricts/choropleth
     * 返回包含街道边界、全区面积、绿化交集面积和绿化率 (%) 的 GeoJSON 集合
     */
    @GetMapping("/api/subdistricts/choropleth")
    public GeoJsonFeatureCollection getSubdistrictChoropleth() {
        return new GeoJsonFeatureCollection(subdistrictRepository.findChoropleth());
    }
}
