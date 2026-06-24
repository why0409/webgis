package com.learngis.webgis.dto;

import java.util.List;

public class GeoJsonFeatureCollection {
    public final String type = "FeatureCollection";
    public List<GeoJsonFeature> features;

    public GeoJsonFeatureCollection(List<GeoJsonFeature> features) {
        this.features = features;
    }
}
