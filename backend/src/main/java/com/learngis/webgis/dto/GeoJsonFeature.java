package com.learngis.webgis.dto;

import com.fasterxml.jackson.annotation.JsonRawValue;

import java.util.Map;

public class GeoJsonFeature {
    public final String type = "Feature";
    public Map<String, Object> properties;

    // geometry 是数据库 ST_AsGeoJSON() 直接输出的 JSON 字符串，原样嵌入，不在 Java 端反序列化几何对象
    @JsonRawValue
    public String geometry;

    public GeoJsonFeature(Map<String, Object> properties, String geometry) {
        this.properties = properties;
        this.geometry = geometry;
    }
}
