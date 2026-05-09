package com.example.jadxmcpserver;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public class InstanceManager {

    private final Map<String, JadxApkAnalyzerAPI> instances = new ConcurrentHashMap<>();
    private final Map<String, String> apkPaths = new ConcurrentHashMap<>();
    private volatile String defaultTarget;

    public Map<String, Object> loadInstance(String instanceId, String apkPath) throws Exception {
        if (instances.containsKey(instanceId)) {
            removeInstance(instanceId);
        }

        JadxApkAnalyzerAPI analyzer = new JadxApkAnalyzerAPI();
        Map<String, Object> info = analyzer.loadApk(apkPath);

        instances.put(instanceId, analyzer);
        apkPaths.put(instanceId, apkPath);

        if (instances.size() == 1) {
            defaultTarget = instanceId;
        }

        info.put("instanceId", instanceId);
        return info;
    }

    public void removeInstance(String instanceId) {
        JadxApkAnalyzerAPI analyzer = instances.remove(instanceId);
        apkPaths.remove(instanceId);
        if (analyzer != null) {
            analyzer.close();
        }
        if (instanceId.equals(defaultTarget)) {
            defaultTarget = instances.isEmpty() ? null : instances.keySet().iterator().next();
        }
    }

    public JadxApkAnalyzerAPI resolve(String target) throws Exception {
        if (target != null && !target.isEmpty()) {
            JadxApkAnalyzerAPI analyzer = instances.get(target);
            if (analyzer == null) {
                throw new Exception("Instance not found: " + target + ". Available: " + instances.keySet());
            }
            return analyzer;
        }

        if (instances.isEmpty()) {
            throw new Exception("No APK loaded. Call load_apk first.");
        }

        if (instances.size() == 1) {
            return instances.values().iterator().next();
        }

        if (defaultTarget != null && instances.containsKey(defaultTarget)) {
            return instances.get(defaultTarget);
        }

        throw new Exception("Multiple instances loaded but no default target set. Call select_target or specify target parameter. Available: " + instances.keySet());
    }

    public void setDefaultTarget(String instanceId) throws Exception {
        if (!instances.containsKey(instanceId)) {
            throw new Exception("Instance not found: " + instanceId + ". Available: " + instances.keySet());
        }
        this.defaultTarget = instanceId;
    }

    public String getDefaultTarget() {
        return defaultTarget;
    }

    public List<Map<String, Object>> listInstances() {
        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, String> entry : apkPaths.entrySet()) {
            Map<String, Object> info = new LinkedHashMap<>();
            info.put("instanceId", entry.getKey());
            info.put("apkPath", entry.getValue());
            info.put("isDefault", entry.getKey().equals(defaultTarget));
            result.add(info);
        }
        return result;
    }

    public boolean isEmpty() {
        return instances.isEmpty();
    }
}
