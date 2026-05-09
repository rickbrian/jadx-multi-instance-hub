package com.example.jadxmcpserver;

import org.springframework.stereotype.Service;
import org.springframework.ai.tool.annotation.Tool;

import java.util.*;
import java.util.logging.Logger;

@Service
public class JadxToolService {

    private static final Logger logger = Logger.getLogger(JadxToolService.class.getName());
    private final InstanceManager instanceManager;

    public JadxToolService(InstanceManager instanceManager) {
        this.instanceManager = instanceManager;
    }

    // ===== Instance Management Tools =====

    @Tool(name = "load_apk", description = "Load an APK file. instanceId is a short name for this target (e.g. 'mobikwik', 'paytm')")
    public Map<String, Object> loadApk(String apkPath, String instanceId) {
        try {
            logger.info("Loading APK: " + apkPath + " as instance: " + instanceId);
            return instanceManager.loadInstance(instanceId, apkPath);
        } catch (Exception e) {
            logger.severe("Error loading APK: " + e.getMessage());
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "list_instances", description = "List all loaded APK instances with their IDs and paths")
    public List<Map<String, Object>> listInstances() {
        return instanceManager.listInstances();
    }

    @Tool(name = "select_target", description = "Set the default target instance for subsequent tool calls")
    public Map<String, String> selectTarget(String instanceId) {
        try {
            instanceManager.setDefaultTarget(instanceId);
            return Map.of("status", "ok", "defaultTarget", instanceId);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    @Tool(name = "current_target", description = "Get the currently selected default target instance")
    public Map<String, Object> currentTarget() {
        String target = instanceManager.getDefaultTarget();
        if (target == null) {
            return Map.of("defaultTarget", "none", "instances", instanceManager.listInstances());
        }
        return Map.of("defaultTarget", target, "instances", instanceManager.listInstances());
    }

    @Tool(name = "remove_instance", description = "Close and remove a loaded APK instance")
    public Map<String, String> removeInstance(String instanceId) {
        try {
            instanceManager.removeInstance(instanceId);
            return Map.of("status", "removed", "instanceId", instanceId);
        } catch (Exception e) {
            return Map.of("error", e.getMessage());
        }
    }

    // ===== Analysis Tools (all with optional target routing) =====

    @Tool(name = "get_all_classes", description = "Get list of all classes. target: instance ID (optional, uses default)")
    public List<String> getAllClasses(String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getAllClasses();
        } catch (Exception e) {
            return List.of("Error: " + e.getMessage());
        }
    }

    @Tool(name = "get_class_source", description = "Get decompiled Java source of a class. target: instance ID (optional)")
    public String getClassSource(String className, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getClassSource(className);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_methods_of_class", description = "Get list of methods in a class. target: instance ID (optional)")
    public List<String> getMethodsOfClass(String className, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getMethodsOfClass(className);
        } catch (Exception e) {
            return List.of("Error: " + e.getMessage());
        }
    }

    @Tool(name = "get_fields_of_class", description = "Get list of fields in a class. target: instance ID (optional)")
    public List<String> getFieldsOfClass(String className, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getFieldsOfClass(className);
        } catch (Exception e) {
            return List.of("Error: " + e.getMessage());
        }
    }

    @Tool(name = "get_method_by_name", description = "Get source code of a specific method. target: instance ID (optional)")
    public String getMethodByName(String className, String methodName, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getMethodSource(className, methodName);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "search_method_by_name", description = "Search for methods across all classes. target: instance ID (optional)")
    public Map<String, List<String>> searchMethodByName(String methodName, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.searchMethod(methodName);
        } catch (Exception e) {
            return Map.of("error", List.of(e.getMessage()));
        }
    }

    @Tool(name = "get_exported_components", description = "Get exported components from manifest. target: instance ID (optional)")
    public List<Map<String, Object>> getExportedComponents(String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getExportedComponents();
        } catch (Exception e) {
            return List.of(Map.of("error", e.getMessage()));
        }
    }

    @Tool(name = "get_android_manifest", description = "Get AndroidManifest.xml content. target: instance ID (optional)")
    public String getAndroidManifest(String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getAndroidManifest();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_main_activity_class", description = "Get main launcher activity class name. target: instance ID (optional)")
    public String getMainActivityClass(String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getMainActivity();
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_all_resource_file_names", description = "Get list of resource file names. target: instance ID (optional)")
    public List<String> getAllResourceFileNames(String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getAllResourceFileNames();
        } catch (Exception e) {
            return List.of("Error: " + e.getMessage());
        }
    }

    @Tool(name = "get_resource_file", description = "Get content of a resource file. target: instance ID (optional)")
    public String getResourceFile(String fileName, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getResourceFile(fileName);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_smali_of_class", description = "Get smali bytecode of a class. target: instance ID (optional)")
    public String getSmaliOfClass(String className, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getSmaliOfClass(className);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }

    @Tool(name = "get_smali_of_method", description = "Get smali bytecode of a method. target: instance ID (optional)")
    public String getSmaliOfMethod(String className, String methodName, String target) {
        try {
            JadxApkAnalyzerAPI analyzer = instanceManager.resolve(target);
            return analyzer.getSmaliOfMethod(className, methodName);
        } catch (Exception e) {
            return "Error: " + e.getMessage();
        }
    }
}
