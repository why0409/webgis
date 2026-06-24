package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.BoundaryRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class BoundaryController {

    private final BoundaryRepository boundaryRepository;

    public BoundaryController(BoundaryRepository boundaryRepository) {
        this.boundaryRepository = boundaryRepository;
    }

    @GetMapping("/api/boundaries/shushan")
    public GeoJsonFeatureCollection getShushanBoundary() {
        return new GeoJsonFeatureCollection(boundaryRepository.findByName("蜀山区"));
    }
}
