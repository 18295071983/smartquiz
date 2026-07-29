package com.oilquiz.app.weather.model;

public class WeatherNowData {
    public String obsTime = "";
    public String temp = "";
    public String feelsLike = "";
    public String iconCode = "";
    public String text = "";
    public String wind360 = "";
    public String windDir = "";
    public String windScale = "";
    public String windSpeed = "";
    public String humidity = "";
    public String precip = "";
    public String pressure = "";
    public String visibility = "";
    public String cloud = "";
    public String dew = "";
    public String highTemp = "";
    public String lowTemp = "";
    public String uvIndex = "";
    public String sunRise = "";
    public String sunSet = "";
    public String cityName = "";
    public String fxLink = "";
    public String updateTime = "";

    public boolean hasValidData() {
        return temp != null && !temp.isEmpty();
    }

    public String getWindDisplay() {
        StringBuilder sb = new StringBuilder();
        if (windDir != null && !windDir.isEmpty()) sb.append(windDir);
        if (windScale != null && !windScale.isEmpty()) sb.append(windScale).append("级");
        if (windSpeed != null && !windSpeed.isEmpty()) sb.append(" ").append(windSpeed).append("km/h");
        return sb.toString().trim();
    }

    public String getTempRange() {
        if (highTemp != null && !highTemp.isEmpty() && lowTemp != null && !lowTemp.isEmpty()) {
            return "最高" + highTemp + "° 最低" + lowTemp + "°";
        }
        return "";
    }
}