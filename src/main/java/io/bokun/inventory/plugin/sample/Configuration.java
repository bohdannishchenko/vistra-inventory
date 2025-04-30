package io.bokun.inventory.plugin.sample;

import javax.json.Json;
import javax.json.JsonArray;
import javax.json.JsonReader;
import java.io.StringReader;
import io.bokun.inventory.common.api.grpc.*;

/**
 * Holder of configuration parameter values.
 */
public final class Configuration {

    static final String VISTRA_API_SCHEME = "VISTRA_API_SCHEME";
    static final String VISTRA_API_HOST = "VISTRA_API_HOST";
    static final String VISTRA_API_PORT = "VISTRA_API_PORT";
    static final String VISTRA_API_PATH = "VISTRA_API_PATH";
    static final String VISTRA_API_USERNAME = "VISTRA_API_USERNAME";
    static final String VISTRA_API_PASSWORD = "VISTRA_API_PASSWORD";
    static final String VISTRA_API_EXTERNAL_PID = "VISTRA_API_EXTERNAL_PID";

    String scheme;
    String host;
    int port;
    String apiPath;
    String username;
    String password;
    String[] externalIds;

    public static String getBokunAccessKey() {
        return System.getenv("BOKUN_ACCESS_KEY");
    }
    
    public static String getBokunSecretKey() {
        return System.getenv("BOKUN_SECRET_KEY");
    }
    
    public static String getBokunApiBaseUrl() {
        return System.getenv().getOrDefault("BOKUN_API_URL", "https://api.bokun.io");
    }

    public static String[] parseJsonArray(String input) {
        try (JsonReader reader = Json.createReader(new StringReader(input))) {
            JsonArray jsonArray = reader.readArray();
            String[] result = new String[jsonArray.size()];
            for (int i = 0; i < jsonArray.size(); i++) {
                result[i] = jsonArray.get(i).toString().replace("\"", ""); // remove quotes if it's a string
            }
            return result;
        }
    }

    private static void setParameterValue(String parameterName, String parameterValue, Configuration configuration) {
        switch (parameterName) {
            // case VISTRA_API_SCHEME: configuration.scheme = parameterValue; break;
            // case VISTRA_API_HOST: configuration.host = parameterValue; break;
            // case VISTRA_API_PORT: configuration.port = Integer.parseInt(parameterValue); break;
            // case VISTRA_API_PATH: configuration.apiPath = parameterValue; break;
            // case VISTRA_API_USERNAME: configuration.username = parameterValue; break;
            // case VISTRA_API_PASSWORD: configuration.password = parameterValue; break;
            case VISTRA_API_EXTERNAL_PID: configuration.externalIds = parseJsonArray(parameterValue); break;
        }
    }

    public static Configuration fromGrpcParameters(Iterable<io.bokun.inventory.common.api.grpc.PluginConfigurationParameterValue> configParameters) {
        Configuration configuration = new Configuration();
        for (PluginConfigurationParameterValue parameterValue : configParameters) {
            setParameterValue(parameterValue.getName(), parameterValue.getValue(), configuration);
        }
        return configuration;
    }

    public static Configuration fromRestParameters(Iterable<io.bokun.inventory.plugin.api.rest.PluginConfigurationParameterValue> configParameters) {
        Configuration configuration = new Configuration();
        for (io.bokun.inventory.plugin.api.rest.PluginConfigurationParameterValue parameterValue : configParameters) {
            setParameterValue(parameterValue.getName(), parameterValue.getValue(), configuration);
        }
        return configuration;
    }
}
