package com.learngis.webgis.controller;

import com.learngis.webgis.dto.GeoJsonFeatureCollection;
import com.learngis.webgis.repository.PoiRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PoiController {

    private final PoiRepository poiRepository;

    public PoiController(PoiRepository poiRepository) {
        this.poiRepository = poiRepository;
    }

    @GetMapping("/api/pois")
    public GeoJsonFeatureCollection getPois() {
        return new GeoJsonFeatureCollection(poiRepository.findAll());
    }
}
