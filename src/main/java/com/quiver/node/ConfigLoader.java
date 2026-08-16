package com.quiver.node;

import com.google.gson.Gson;
import com.google.gson.reflect.TypeToken;

import java.io.FileReader;
import java.io.IOException;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;

public class ConfigLoader {

    public static List<NodeConfig> loadPeers(String configPath) {
        try (FileReader reader = new FileReader(configPath)) {
            Gson gson = new Gson();
            Type mapType = new TypeToken<Map<String, List<NodeConfig>>>() {}.getType();
            Map<String, List<NodeConfig>> configData = gson.fromJson(reader, mapType);
            return configData.get("nodes");
        } catch (IOException e) {
            System.out.println("Could not read config file '" + configPath + "': " + e.getMessage());
            System.out.println("Falling back to default ports (9001, 9002, 9003).");
            return null;   
        }
    }
}
