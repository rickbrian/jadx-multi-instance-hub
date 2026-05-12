package com.example.jadxmcpserver.core;

import com.example.jadxmcpserver.model.CallGraphNode;
import com.example.jadxmcpserver.model.ExportedComponent;
import jadx.api.JadxArgs;
import jadx.api.JadxDecompiler;
import jadx.api.JavaClass;
import jadx.api.JavaMethod;
import jadx.api.JavaField;
import jadx.api.ResourceFile;
import jadx.api.ICodeInfo;
import jadx.core.dex.nodes.ClassNode;
import jadx.core.dex.nodes.MethodNode;
import jadx.core.xmlgen.ResContainer;
import jadx.zip.IZipEntry;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import java.io.File;
import java.io.InputStream;
import java.io.StringReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Core JADX APK Analyzer - Provides analysis functionality without UI
 * This class handles all APK analysis operations including:
 * - APK loading and decompilation
 * - Manifest parsing and component extraction
 * - Class and method analysis
 * - Call graph generation
 * - String and code search functionality
 */
public class JadxAnalyzerCore {

    private JadxDecompiler jadx;
    private String apkPath;
    private List<ExportedComponent> exportedComponents;
    private String manifestContent;
    private String packageName;

    private Map<String, JavaClass> classIndex = new HashMap<>();
    private Map<String, Set<String>> methodIndex = new HashMap<>();
    private Set<String> dexStringPool = new HashSet<>();
    private Map<String, Set<String>> dexStringRefs = new HashMap<>();
    private Map<String, String> loadTimings = new HashMap<>();

    // Background decompilation
    private ExecutorService decompileExecutor;
    private final AtomicInteger decompileProgress = new AtomicInteger(0);
    private final AtomicInteger decompileTotal = new AtomicInteger(0);
    private final AtomicInteger decompileFailed = new AtomicInteger(0);
    private volatile boolean decompileDone = false;
    private volatile boolean decompileCancelled = false;
    private volatile long decompileStartTime = 0;

    private static final java.util.logging.Logger logger =
        java.util.logging.Logger.getLogger(JadxAnalyzerCore.class.getName());
    
    public JadxAnalyzerCore(String apkPath) {
        this.apkPath = apkPath;
    }
    
    /**
     * Load and initialize the APK for analysis
     * @return true if loading was successful, false otherwise
     */
    public boolean loadApk() {
        File apkFile = new File(apkPath);
        
        if (!apkFile.exists()) {
            throw new RuntimeException("APK file not found: " + apkPath);
        }
        
        // Close previous instance if exists
        close();
        
        // Configure JADX
        JadxArgs jadxArgs = new JadxArgs();
        jadxArgs.setInputFile(apkFile);
        jadxArgs.setDeobfuscationOn(true);
        jadxArgs.setDeobfuscationMinLength(2);
        jadxArgs.setDeobfuscationMaxLength(64);
        jadxArgs.setShowInconsistentCode(true);
        
        try {
            long t0 = System.currentTimeMillis();
            jadx = new JadxDecompiler(jadxArgs);
            jadx.load();
            long t1 = System.currentTimeMillis();

            // Build DEX string pool (fast, direct DEX parsing, no decompilation)
            buildDexStringPool();
            long t2 = System.currentTimeMillis();

            // Build indexes
            buildIndexes();
            long t3 = System.currentTimeMillis();

            // Load manifest
            loadManifest();
            long t4 = System.currentTimeMillis();

            loadTimings = Map.of(
                "jadx.load", (t1 - t0) + "ms",
                "dexStringPool", (t2 - t1) + "ms",
                "buildIndexes", (t3 - t2) + "ms",
                "loadManifest", (t4 - t3) + "ms",
                "total", (t4 - t0) + "ms"
            );
            logger.info("Load timings: " + loadTimings);

            // Start background decompilation
            startBackgroundDecompile();

            return true;
        } catch (Exception e) {
            throw new RuntimeException("Error loading APK: " + e.getMessage(), e);
        }
    }
    
    /**
     * Get basic APK information
     */
    public Map<String, Object> getApkInfo() {
        Map<String, Object> info = new HashMap<>();
        info.put("apkPath", apkPath);
        info.put("packageName", packageName);
        info.put("totalClasses", jadx != null ? jadx.getClasses().size() : 0);
        info.put("exportedComponents", exportedComponents != null ? exportedComponents.size() : 0);
        info.put("mainActivity", getMainActivityClass());
        info.put("loadTimings", loadTimings);
        return info;
    }
    
    /**
     * Get the AndroidManifest.xml content
     */
    public String getAndroidManifest() {
        return manifestContent;
    }
    
    /**
     * Get the package name from manifest
     */
    public String getPackageName() {
        return packageName;
    }
    
    /**
     * Get main activity class from AndroidManifest.xml
     */
    public String getMainActivityClass() {
        if (manifestContent == null) {
            return null;
        }
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            
            NodeList activities = doc.getElementsByTagName("activity");
            
            for (int i = 0; i < activities.getLength(); i++) {
                Element activity = (Element) activities.item(i);
                NodeList intentFilters = activity.getElementsByTagName("intent-filter");
                
                for (int j = 0; j < intentFilters.getLength(); j++) {
                    Element intentFilter = (Element) intentFilters.item(j);
                    NodeList actions = intentFilter.getElementsByTagName("action");
                    NodeList categories = intentFilter.getElementsByTagName("category");
                    
                    boolean hasMainAction = false;
                    boolean hasLauncherCategory = false;
                    
                    for (int k = 0; k < actions.getLength(); k++) {
                        Element action = (Element) actions.item(k);
                        if ("android.intent.action.MAIN".equals(action.getAttribute("android:name"))) {
                            hasMainAction = true;
                        }
                    }
                    
                    for (int k = 0; k < categories.getLength(); k++) {
                        Element category = (Element) categories.item(k);
                        if ("android.intent.category.LAUNCHER".equals(category.getAttribute("android:name"))) {
                            hasLauncherCategory = true;
                        }
                    }
                    
                    if (hasMainAction && hasLauncherCategory) {
                        String activityName = activity.getAttribute("android:name");
                        if (activityName.startsWith(".")) {
                            activityName = packageName + activityName;
                        } else if (!activityName.contains(".")) {
                            activityName = packageName + "." + activityName;
                        }
                        return activityName;
                    }
                }
            }
        } catch (Exception e) {
            throw new RuntimeException("Error finding main activity: " + e.getMessage(), e);
        }
        
        return null;
    }
    
    /**
     * Get all classes in the APK
     */
    public List<String> getAllClasses() {
        List<String> classNames = new ArrayList<>();
        for (JavaClass javaClass : jadx.getClasses()) {
            classNames.add(javaClass.getFullName());
        }
        Collections.sort(classNames);
        return classNames;
    }
    
    /**
     * Get full source code of a given class
     */
    public String getClassSource(String className) {
        JavaClass javaClass = classIndex.get(className);
        if (javaClass != null) {
            return javaClass.getCode();
        }
        return null;
    }
    
    /**
     * Search for classes by name (supports partial matching)
     */
    public List<JavaClass> searchClasses(String className) {
        List<JavaClass> matches = new ArrayList<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            String fullName = javaClass.getFullName();
            String simpleName = javaClass.getName();
            
            // Check for matches (exact, partial, or case-insensitive)
            if (fullName.equals(className) || 
                simpleName.equals(className) ||
                fullName.endsWith("." + className) ||
                fullName.toLowerCase().contains(className.toLowerCase()) ||
                simpleName.toLowerCase().contains(className.toLowerCase())) {
                matches.add(javaClass);
            }
        }
        
        return matches;
    }
    
    /**
     * Get detailed information about a class
     */
    public Map<String, Object> getClassDetails(String className) {
        JavaClass javaClass = findClass(className);
        if (javaClass == null) {
            return null;
        }
        
        Map<String, Object> details = new HashMap<>();
        details.put("fullName", javaClass.getFullName());
        details.put("package", javaClass.getPackage());
        details.put("methodCount", javaClass.getMethods().size());
        details.put("fieldCount", javaClass.getFields().size());
        details.put("methods", getMethodsOfClass(className));
        details.put("fields", getFieldsOfClass(className));
        details.put("sourceCode", javaClass.getCode());
        
        return details;
    }
    
    /**
     * List all methods in a specific class
     */
    public List<String> getMethodsOfClass(String className) {
        List<String> methods = new ArrayList<>();
        JavaClass javaClass = classIndex.get(className);
        if (javaClass != null) {
            for (JavaMethod method : javaClass.getMethods()) {
                methods.add(method.getFullName());
            }
        }
        return methods;
    }
    
    /**
     * List all fields in a specific class
     */
    public List<String> getFieldsOfClass(String className) {
        List<String> fields = new ArrayList<>();
        JavaClass javaClass = classIndex.get(className);
        if (javaClass != null) {
            for (JavaField field : javaClass.getFields()) {
                fields.add(field.getType() + " " + field.getName());
            }
        }
        return fields;
    }
    
    /**
     * Get source code of a specific method
     */
    public String getMethodByName(String className, String methodName) {
        JavaClass targetClass = findClass(className);
        if (targetClass == null) {
            return null;
        }
        
        String classCode = targetClass.getCode();
        return extractMethodCode(classCode, methodName);
    }
    
    /**
     * Search for methods by name across all classes
     */
    public Map<String, List<String>> searchMethodByName(String methodName) {
        Map<String, List<String>> results = new HashMap<>();

        for (Map.Entry<String, Set<String>> entry : methodIndex.entrySet()) {
            String name = entry.getKey();
            if (name.equals(methodName) || name.contains(methodName)) {
                for (String className : entry.getValue()) {
                    JavaClass javaClass = classIndex.get(className);
                    if (javaClass == null) continue;
                    List<String> methods = new ArrayList<>();
                    for (JavaMethod method : javaClass.getMethods()) {
                        if (method.getName().equals(name)) {
                            methods.add(method.getFullName());
                        }
                    }
                    if (!methods.isEmpty()) {
                        results.computeIfAbsent(className, k -> new ArrayList<>()).addAll(methods);
                    }
                }
            }
        }

        return results;
    }
    
    
    /**
     * Get all exported components from the manifest
     */
    public List<ExportedComponent> getExportedComponents() {
        return exportedComponents != null ? new ArrayList<>(exportedComponents) : new ArrayList<>();
    }
    
    /**
     * Get exported components grouped by type
     */
    public Map<String, List<ExportedComponent>> getExportedComponentsByType() {
        Map<String, List<ExportedComponent>> grouped = new HashMap<>();
        
        if (exportedComponents != null) {
            for (ExportedComponent component : exportedComponents) {
                grouped.computeIfAbsent(component.type, k -> new ArrayList<>()).add(component);
            }
        }
        
        return grouped;
    }
    
    /**
     * Get exported components as Maps for API serialization
     */
    public List<Map<String, Object>> getExportedComponentsAsMap() {
        List<Map<String, Object>> result = new ArrayList<>();
        if (exportedComponents != null) {
            for (ExportedComponent component : exportedComponents) {
                result.add(component.toMap());
            }
        }
        return result;
    }
    
    /**
     * Check if APK is loaded
     */
    public boolean isLoaded() {
        return jadx != null;
    }
    
    /**
     * Ensure APK is loaded, throw exception if not
     */
    public void checkLoaded() {
        if (!isLoaded()) {
            throw new RuntimeException("No APK loaded. Call loadApk() first.");
        }
    }
    
    /**
     * Generate call graph for a specific method
     */
    public CallGraphResult generateCallGraphForMethod(String targetMethod) {
        // Find all methods that match the target
        Set<CallGraphNode> targetNodes = findTargetMethods(targetMethod);
        
        if (targetNodes.isEmpty()) {
            return new CallGraphResult(false, "Method not found: " + targetMethod, null, null, getSimilarMethods(targetMethod));
        }
        
        // Build call graph starting from target methods
        Map<String, CallGraphNode> allNodes = new HashMap<>();
        Set<String> visited = new HashSet<>();
        
        for (CallGraphNode targetNode : targetNodes) {
            findCallersRecursively(targetNode, allNodes, visited, 0, 5); // Max depth 5
        }
        
        // Find entry points
        Set<CallGraphNode> entryPoints = findEntryPoints(targetNodes, allNodes);
        
        return new CallGraphResult(true, "Call graph generated successfully", targetNodes, entryPoints, null);
    }
    
    /**
     * Result class for call graph analysis
     */
    public static class CallGraphResult {
        public final boolean success;
        public final String message;
        public final Set<CallGraphNode> targetNodes;
        public final Set<CallGraphNode> entryPoints;
        public final Set<String> suggestions;
        
        public CallGraphResult(boolean success, String message, Set<CallGraphNode> targetNodes, 
                              Set<CallGraphNode> entryPoints, Set<String> suggestions) {
            this.success = success;
            this.message = message;
            this.targetNodes = targetNodes;
            this.entryPoints = entryPoints;
            this.suggestions = suggestions;
        }
    }
    
    // Private helper methods

    private void buildIndexes() {
        classIndex.clear();
        methodIndex.clear();
        List<JavaClass> allClasses = jadx.getClasses();
        logger.info("Building indexes for " + allClasses.size() + " classes...");
        int count = 0;
        for (JavaClass javaClass : allClasses) {
            classIndex.put(javaClass.getFullName(), javaClass);
            try {
                ClassNode clsNode = javaClass.getClassNode();
                for (MethodNode mth : clsNode.getMethods()) {
                    String mthName = mth.getMethodInfo().getName();
                    if (!mthName.equals("<init>") && !mthName.equals("<clinit>")) {
                        methodIndex.computeIfAbsent(mthName, k -> new HashSet<>())
                                   .add(javaClass.getFullName());
                    }
                }
            } catch (Exception e) {
                // skip classes that fail
            }
            count++;
            if (count % 10000 == 0) {
                logger.info("Indexed " + count + "/" + allClasses.size() + " classes");
            }
        }
        logger.info("Index complete: " + classIndex.size() + " classes, " + methodIndex.size() + " unique method names");
    }

    private void startBackgroundDecompile() {
        stopBackgroundDecompile();

        // Order: main package first, then the rest
        List<JavaClass> ordered = new ArrayList<>();
        List<JavaClass> rest = new ArrayList<>();
        for (JavaClass cls : classIndex.values()) {
            if (packageName != null && cls.getFullName().startsWith(packageName)) {
                ordered.add(cls);
            } else {
                rest.add(cls);
            }
        }
        ordered.addAll(rest);

        decompileTotal.set(ordered.size());
        decompileProgress.set(0);
        decompileFailed.set(0);
        decompileDone = false;
        decompileCancelled = false;
        decompileStartTime = System.currentTimeMillis();

        decompileExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "bg-decompile");
            t.setDaemon(true);
            t.setPriority(Thread.MIN_PRIORITY);
            return t;
        });

        decompileExecutor.submit(() -> {
            int total = ordered.size();
            logger.info("Background decompile started: " + total + " classes");
            for (int i = 0; i < total; i++) {
                if (decompileCancelled || Thread.currentThread().isInterrupted()) break;
                try {
                    ordered.get(i).getCode();
                } catch (Exception e) {
                    decompileFailed.incrementAndGet();
                }
                decompileProgress.incrementAndGet();
                if ((i + 1) % 5000 == 0) {
                    logger.info("Background decompile: " + (i + 1) + "/" + total);
                }
            }
            decompileDone = true;
            long elapsed = System.currentTimeMillis() - decompileStartTime;
            logger.info("Background decompile done: " + decompileProgress.get() + "/" + total
                    + ", failed=" + decompileFailed.get() + ", time=" + elapsed + "ms");
        });
    }

    private void stopBackgroundDecompile() {
        decompileCancelled = true;
        if (decompileExecutor != null) {
            decompileExecutor.shutdownNow();
            try {
                decompileExecutor.awaitTermination(10, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            decompileExecutor = null;
        }
    }

    public Map<String, Object> getDecompileStatus() {
        Map<String, Object> status = new LinkedHashMap<>();
        int progress = decompileProgress.get();
        int total = decompileTotal.get();
        int failed = decompileFailed.get();
        status.put("progress", progress);
        status.put("total", total);
        status.put("failed", failed);
        status.put("done", decompileDone);
        if (total > 0) {
            status.put("percent", String.format("%.1f%%", progress * 100.0 / total));
        }
        if (progress > 0 && !decompileDone) {
            long elapsed = System.currentTimeMillis() - decompileStartTime;
            long eta = (elapsed / progress) * (total - progress);
            status.put("eta", (eta / 1000) + "s");
        }
        return status;
    }

    private void buildDexStringPool() {
        dexStringPool.clear();
        dexStringRefs.clear();
        long start = System.currentTimeMillis();
        try (ZipFile zip = new ZipFile(apkPath)) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name.matches("classes\\d*\\.dex")) {
                    try (InputStream is = zip.getInputStream(entry)) {
                        byte[] dexData = is.readAllBytes();
                        parseDexFile(dexData);
                    }
                }
            }
        } catch (Exception e) {
            logger.warning("Failed to build DEX string pool: " + e.getMessage());
        }
        long elapsed = System.currentTimeMillis() - start;
        logger.info("DEX index: " + dexStringPool.size() + " pool strings, "
                + dexStringRefs.size() + " referenced strings in " + elapsed + "ms");
    }

    private void parseDexFile(byte[] dex) {
        if (dex.length < 0x70) return;
        if (dex[0] != 'd' || dex[1] != 'e' || dex[2] != 'x' || dex[3] != '\n') return;

        ByteBuffer buf = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN);

        // 1. string_ids → string values
        int stringIdsSize = buf.getInt(0x38);
        int stringIdsOff = buf.getInt(0x3C);
        if (stringIdsSize < 0 || stringIdsSize > dex.length / 4) return;
        if (stringIdsOff < 0 || stringIdsOff + (long) stringIdsSize * 4 > dex.length) return;
        String[] strings = new String[stringIdsSize];
        for (int i = 0; i < stringIdsSize; i++) {
            int off = buf.getInt(stringIdsOff + i * 4);
            if (off >= 0 && off < dex.length) {
                strings[i] = readMutf8(dex, off);
            }
        }
        for (String s : strings) {
            if (s != null && s.length() > 1) {
                dexStringPool.add(s);
            }
        }

        // 2. type_ids → type descriptors
        int typeIdsSize = buf.getInt(0x40);
        int typeIdsOff = buf.getInt(0x44);
        if (typeIdsSize < 0 || typeIdsSize > dex.length / 4) return;
        if (typeIdsOff < 0 || typeIdsOff + (long) typeIdsSize * 4 > dex.length) return;
        String[] typeDescs = new String[typeIdsSize];
        for (int i = 0; i < typeIdsSize; i++) {
            int descIdx = buf.getInt(typeIdsOff + i * 4);
            if (descIdx >= 0 && descIdx < stringIdsSize) {
                typeDescs[i] = strings[descIdx];
            }
        }

        // 3. method_ids → class type index + method name
        int methodIdsSize = buf.getInt(0x58);
        int methodIdsOff = buf.getInt(0x5C);
        if (methodIdsSize < 0 || methodIdsSize > dex.length / 8) return;
        if (methodIdsOff < 0 || methodIdsOff + (long) methodIdsSize * 8 > dex.length) return;
        int[] methodClassIdx = new int[methodIdsSize];
        int[] methodNameIdx = new int[methodIdsSize];
        for (int i = 0; i < methodIdsSize; i++) {
            int base = methodIdsOff + i * 8;
            methodClassIdx[i] = buf.getShort(base) & 0xFFFF;
            methodNameIdx[i] = buf.getInt(base + 4);
        }

        // 4. class_defs → class_data → code_items → scan const-string
        int classDefsSize = buf.getInt(0x60);
        int classDefsOff = buf.getInt(0x64);
        if (classDefsSize < 0 || classDefsSize > dex.length / 32) return;
        if (classDefsOff < 0 || classDefsOff + (long) classDefsSize * 32 > dex.length) return;

        for (int c = 0; c < classDefsSize; c++) {
            int defBase = classDefsOff + c * 32;
            if (defBase + 32 > dex.length) break;
            int classIdx = buf.getInt(defBase);
            int classDataOff = buf.getInt(defBase + 24);
            if (classDataOff == 0 || classDataOff < 0 || classDataOff >= dex.length) continue;

            String className = (classIdx >= 0 && classIdx < typeIdsSize)
                    ? dexTypeToClassName(typeDescs[classIdx]) : null;
            if (className == null) continue;

            int[] pos = {classDataOff};
            int staticFieldsSize = readUleb128(dex, pos);
            int instanceFieldsSize = readUleb128(dex, pos);
            int directMethodsSize = readUleb128(dex, pos);
            int virtualMethodsSize = readUleb128(dex, pos);

            if (staticFieldsSize < 0 || instanceFieldsSize < 0
                    || directMethodsSize < 0 || virtualMethodsSize < 0) continue;

            // skip encoded_field entries (2 ULEB128 each)
            for (int f = 0; f < staticFieldsSize + instanceFieldsSize; f++) {
                readUleb128(dex, pos);
                readUleb128(dex, pos);
                if (pos[0] >= dex.length) break;
            }

            // scan direct methods
            scanEncodedMethods(dex, buf, pos, directMethodsSize,
                    strings, stringIdsSize, methodClassIdx, methodNameIdx, methodIdsSize, className);
            // scan virtual methods
            scanEncodedMethods(dex, buf, pos, virtualMethodsSize,
                    strings, stringIdsSize, methodClassIdx, methodNameIdx, methodIdsSize, className);
        }
    }

    private void scanEncodedMethods(byte[] dex, ByteBuffer buf, int[] pos, int count,
                                     String[] strings, int stringIdsSize,
                                     int[] methodClassIdx, int[] methodNameIdx, int methodIdsSize,
                                     String className) {
        int methodIdx = 0;
        for (int m = 0; m < count; m++) {
            if (pos[0] >= dex.length) break;
            int diff = readUleb128(dex, pos);
            readUleb128(dex, pos); // access_flags
            int codeOff = readUleb128(dex, pos);
            methodIdx += diff;

            if (codeOff == 0) continue;
            if (methodIdx < 0 || methodIdx >= methodIdsSize) continue;
            if (codeOff + 16 > dex.length) continue;

            String methodName = (methodNameIdx[methodIdx] >= 0 && methodNameIdx[methodIdx] < stringIdsSize)
                    ? strings[methodNameIdx[methodIdx]] : null;
            if (methodName == null) continue;

            int insnsSize = buf.getInt(codeOff + 12);
            int insnsOff = codeOff + 16;
            if (insnsSize <= 0 || insnsOff + (long) insnsSize * 2 > dex.length) continue;

            String ref = className + "." + methodName;

            for (int i = 0; i < insnsSize; i++) {
                int insn = buf.getShort(insnsOff + i * 2) & 0xFFFF;
                int opcode = insn & 0xFF;

                if (opcode == 0x1a && i + 1 < insnsSize) {
                    int strIdx = buf.getShort(insnsOff + (i + 1) * 2) & 0xFFFF;
                    if (strIdx < stringIdsSize && strings[strIdx] != null) {
                        dexStringRefs.computeIfAbsent(strings[strIdx], k -> new LinkedHashSet<>())
                                .add(ref);
                    }
                } else if (opcode == 0x1b && i + 2 < insnsSize) {
                    int lo = buf.getShort(insnsOff + (i + 1) * 2) & 0xFFFF;
                    int hi = buf.getShort(insnsOff + (i + 2) * 2) & 0xFFFF;
                    int strIdx = lo | (hi << 16);
                    if (strIdx >= 0 && strIdx < stringIdsSize && strings[strIdx] != null) {
                        dexStringRefs.computeIfAbsent(strings[strIdx], k -> new LinkedHashSet<>())
                                .add(ref);
                    }
                }
            }
        }
    }

    private static int readUleb128(byte[] data, int[] pos) {
        int result = 0;
        int shift = 0;
        while (pos[0] < data.length && shift < 35) {
            int b = data[pos[0]++] & 0xFF;
            result |= (b & 0x7F) << shift;
            if ((b & 0x80) == 0) break;
            shift += 7;
        }
        return result;
    }

    private static String dexTypeToClassName(String descriptor) {
        if (descriptor == null || descriptor.length() < 3 || descriptor.charAt(0) != 'L') return null;
        return descriptor.substring(1, descriptor.length() - 1).replace('/', '.');
    }

    private static String readMutf8(byte[] data, int offset) {
        int pos = offset;
        while (pos < data.length && (data[pos] & 0x80) != 0) pos++;
        if (pos >= data.length) return null;
        pos++;

        int start = pos;
        while (pos < data.length && data[pos] != 0) pos++;

        try {
            return new String(data, start, pos - start, "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
    private void extractPackageName() {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestContent)));
            packageName = doc.getDocumentElement().getAttribute("package");
        } catch (Exception e) {
            throw new RuntimeException("Error extracting package name: " + e.getMessage(), e);
        }
    }
    
    private void loadManifest() {
        List<ResourceFile> resources = jadx.getResources();
        
        for (ResourceFile resource : resources) {
            String resName = resource.getOriginalName();
            if (resName.equals("AndroidManifest.xml")) {
                try {
                    ResContainer resContainer = resource.loadContent();
                    if (resContainer != null) {
                        ICodeInfo codeInfo = resContainer.getText();
                        if (codeInfo != null) {
                            manifestContent = codeInfo.toString();
                        } else if (resContainer.getDecodedData() != null) {
                            manifestContent = new String(resContainer.getDecodedData());
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading manifest: " + e.getMessage(), e);
                }
                break;
            }
        }
        
        if (manifestContent != null) {
            exportedComponents = parseManifest(manifestContent);
            extractPackageName();
        }
    }
    
    private JavaClass findClass(String className) {
        return classIndex.get(className);
    }
    
    private String extractMethodCode(String classCode, String methodName) {
        String methodPattern = "(public|private|protected|static|final|native|synchronized|abstract|transient)+" +
                              "[^{]+" + Pattern.quote(methodName) + "\\s*\\([^{]*\\{";
        
        Pattern pattern = Pattern.compile(methodPattern);
        Matcher matcher = pattern.matcher(classCode);
        
        if (matcher.find()) {
            int start = matcher.start();
            int braceStart = classCode.indexOf("{", start);
            
            if (braceStart != -1) {
                // Find matching closing brace
                int braceCount = 1;
                int pos = braceStart + 1;
                
                while (pos < classCode.length() && braceCount > 0) {
                    char ch = classCode.charAt(pos);
                    if (ch == '{') braceCount++;
                    else if (ch == '}') braceCount--;
                    pos++;
                }
                
                if (braceCount == 0) {
                    return classCode.substring(start, pos);
                }
            }
        }
        
        return null;
    }
    
    private String extractMethodSmali(String classSmali, String methodName) {
        String[] lines = classSmali.split("\n");
        StringBuilder methodSmali = new StringBuilder();
        boolean inMethod = false;
        boolean foundMethod = false;
        
        for (String line : lines) {
            // Look for method declaration
            if (line.contains(".method ") && line.contains(" " + methodName + "(")) {
                inMethod = true;
                foundMethod = true;
                methodSmali.append(line).append("\n");
            } else if (inMethod && line.contains(".end method")) {
                methodSmali.append(line).append("\n");
                break;
            } else if (inMethod) {
                methodSmali.append(line).append("\n");
            }
        }
        
        return foundMethod ? methodSmali.toString() : null;
    }
    
    private Set<CallGraphNode> findTargetMethods(String targetMethod) {
        Set<CallGraphNode> targetNodes = new HashSet<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                String methodName = method.getName();
                String fullName = javaClass.getFullName() + "." + methodName;
                
                // Check if this method matches our target
                if (methodName.equals(targetMethod) || 
                    fullName.endsWith("." + targetMethod) ||
                    (targetMethod.contains(".") && fullName.endsWith(targetMethod))) {
                    
                    CallGraphNode node = new CallGraphNode(javaClass.getFullName(), methodName);
                    targetNodes.add(node);
                }
            }
        }
        
        return targetNodes;
    }
    
    private void findCallersRecursively(CallGraphNode targetNode, Map<String, CallGraphNode> allNodes, 
                                       Set<String> visited, int depth, int maxDepth) {
        if (depth >= maxDepth || visited.contains(targetNode.fullSignature)) {
            return;
        }
        
        visited.add(targetNode.fullSignature);
        allNodes.put(targetNode.fullSignature, targetNode);
        
        // Search all classes for methods that call this target
        for (JavaClass javaClass : jadx.getClasses()) {
            String classCode = javaClass.getCode();
            
            // Quick check if this class might contain calls to our target method
            if (!classCode.contains(targetNode.methodName)) {
                continue;
            }
            
            // Check each method in this class
            for (JavaMethod method : javaClass.getMethods()) {
                if (methodCallsTarget(javaClass, method, targetNode)) {
                    CallGraphNode callerNode = allNodes.computeIfAbsent(
                        javaClass.getFullName() + "." + method.getName(),
                        k -> new CallGraphNode(javaClass.getFullName(), method.getName())
                    );
                    
                    targetNode.callers.add(callerNode);
                    
                    // Recursively find callers of this caller
                    findCallersRecursively(callerNode, allNodes, visited, depth + 1, maxDepth);
                }
            }
        }
    }
    
    private boolean methodCallsTarget(JavaClass callerClass, JavaMethod callerMethod, CallGraphNode targetNode) {
        try {
            String methodCode = extractMethodCode(callerClass.getCode(), callerMethod.getName());
            if (methodCode == null) {
                return false;
            }
            
            // Check for method call patterns
            // 1. Direct method call: methodName(
            if (methodCode.contains(targetNode.methodName + "(")) {
                return true;
            }
            
            // 2. Qualified call: ClassName.methodName( or object.methodName(
            Pattern qualifiedCall = Pattern.compile("\\b\\w+\\." + Pattern.quote(targetNode.methodName) + "\\s*\\(");
            if (qualifiedCall.matcher(methodCode).find()) {
                return true;
            }
            
            // 3. For specific patterns like WebView.loadUrl
            if (targetNode.methodName.equals("loadUrl") && methodCode.toLowerCase().contains("webview")) {
                Pattern webViewLoadUrl = Pattern.compile("\\bwebView\\w*\\.loadUrl\\s*\\(", Pattern.CASE_INSENSITIVE);
                if (webViewLoadUrl.matcher(methodCode).find()) {
                    return true;
                }
            }
            
        } catch (Exception e) {
            // Silently ignore parsing errors
        }
        
        return false;
    }
    
    private Set<CallGraphNode> findEntryPoints(Set<CallGraphNode> targetNodes, Map<String, CallGraphNode> allNodes) {
        Set<CallGraphNode> entryPoints = new HashSet<>();
        
        // A node is an entry point if no other nodes in the graph call it
        for (CallGraphNode node : allNodes.values()) {
            boolean isEntry = true;
            
            for (CallGraphNode other : allNodes.values()) {
                if (other != node && other.callers.contains(node)) {
                    isEntry = false;
                    break;
                }
            }
            
            if (isEntry && !targetNodes.contains(node)) {
                entryPoints.add(node);
            }
        }
        
        return entryPoints;
    }
    
    private Set<String> getSimilarMethods(String targetMethod) {
        Set<String> suggestions = new HashSet<>();
        
        for (JavaClass javaClass : jadx.getClasses()) {
            for (JavaMethod method : javaClass.getMethods()) {
                if (method.getName().toLowerCase().contains(targetMethod.toLowerCase())) {
                    suggestions.add(method.getName());
                    if (suggestions.size() >= 10) {
                        return suggestions;
                    }
                }
            }
        }
        
        return suggestions;
    }
    
    private List<ExportedComponent> parseManifest(String manifestXml) {
        List<ExportedComponent> components = new ArrayList<>();
        
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(manifestXml)));
            doc.getDocumentElement().normalize();
            
            // Get package name
            String packageName = doc.getDocumentElement().getAttribute("package");
            
            // Component types to check
            String[] componentTypes = {"activity", "service", "receiver", "provider"};
            
            for (String componentType : componentTypes) {
                NodeList nodeList = doc.getElementsByTagName(componentType);
                
                for (int i = 0; i < nodeList.getLength(); i++) {
                    Node node = nodeList.item(i);
                    
                    if (node.getNodeType() == Node.ELEMENT_NODE) {
                        Element element = (Element) node;
                        ExportedComponent component = analyzeComponent(element, componentType, packageName);
                        
                        if (component != null && component.exported) {
                            components.add(component);
                        }
                    }
                }
            }
            
        } catch (Exception e) {
            throw new RuntimeException("Error parsing manifest: " + e.getMessage(), e);
        }
        
        return components;
    }
    
    private ExportedComponent analyzeComponent(Element element, String type, String packageName) {
        ExportedComponent component = new ExportedComponent();
        component.type = type;
        
        // Get component name
        String name = element.getAttribute("android:name");
        if (name.startsWith(".")) {
            name = packageName + name;
        } else if (!name.contains(".")) {
            name = packageName + "." + name;
        }
        component.name = name;
        
        // Check if explicitly exported
        String exportedAttr = element.getAttribute("android:exported");
        boolean hasIntentFilter = hasIntentFilter(element);
        
        if ("true".equals(exportedAttr)) {
            component.exported = true;
        } else if ("false".equals(exportedAttr)) {
            component.exported = false;
        } else {
            // If not explicitly set, components with intent-filters are exported by default
            component.exported = hasIntentFilter;
        }
        
        // Get permission if any
        component.permission = element.getAttribute("android:permission");
        
        // Extract intent filters
        NodeList intentFilters = element.getElementsByTagName("intent-filter");
        for (int i = 0; i < intentFilters.getLength(); i++) {
            Node filterNode = intentFilters.item(i);
            if (filterNode.getNodeType() == Node.ELEMENT_NODE) {
                String filterDesc = extractIntentFilter((Element) filterNode);
                if (!filterDesc.isEmpty()) {
                    component.intentFilters.add(filterDesc);
                }
            }
        }
        
        return component;
    }
    
    private boolean hasIntentFilter(Element element) {
        NodeList intentFilters = element.getElementsByTagName("intent-filter");
        return intentFilters.getLength() > 0;
    }
    
    private String extractIntentFilter(Element intentFilter) {
        StringBuilder sb = new StringBuilder();
        
        // Extract actions
        NodeList actions = intentFilter.getElementsByTagName("action");
        for (int i = 0; i < actions.getLength(); i++) {
            Element action = (Element) actions.item(i);
            String actionName = action.getAttribute("android:name");
            if (!actionName.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Action: ").append(actionName);
            }
        }
        
        // Extract categories
        NodeList categories = intentFilter.getElementsByTagName("category");
        for (int i = 0; i < categories.getLength(); i++) {
            Element category = (Element) categories.item(i);
            String categoryName = category.getAttribute("android:name");
            if (!categoryName.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Category: ").append(categoryName);
            }
        }
        
        // Extract data elements
        NodeList dataElements = intentFilter.getElementsByTagName("data");
        for (int i = 0; i < dataElements.getLength(); i++) {
            Element data = (Element) dataElements.item(i);
            String scheme = data.getAttribute("android:scheme");
            String host = data.getAttribute("android:host");
            String path = data.getAttribute("android:path");
            String mimeType = data.getAttribute("android:mimeType");
            
            if (!scheme.isEmpty() || !host.isEmpty() || !path.isEmpty() || !mimeType.isEmpty()) {
                if (sb.length() > 0) sb.append(", ");
                sb.append("Data: ");
                if (!scheme.isEmpty()) sb.append("scheme=").append(scheme).append(" ");
                if (!host.isEmpty()) sb.append("host=").append(host).append(" ");
                if (!path.isEmpty()) sb.append("path=").append(path).append(" ");
                if (!mimeType.isEmpty()) sb.append("mimeType=").append(mimeType);
            }
        }
        
        return sb.toString();
    }
    
    /**
     * Get all resource file names in the APK
     */
    public List<String> getAllResourceFileNames() {
        checkLoaded();
        List<String> resourceNames = new ArrayList<>();
        
        List<ResourceFile> resources = jadx.getResources();
        for (ResourceFile resource : resources) {
            resourceNames.add(resource.getOriginalName());
        }
        
        Collections.sort(resourceNames);
        return resourceNames;
    }
    
    /**
     * Get content of a specific resource file
     */
    public String getResourceFile(String fileName) {
        checkLoaded();
        
        List<ResourceFile> resources = jadx.getResources();
        for (ResourceFile resource : resources) {
            if (resource.getOriginalName().equals(fileName)) {
                try {
                    // Try using getZipEntry first
                    IZipEntry zipEntry = resource.getZipEntry();
                    if (zipEntry != null) {
                        try (InputStream ris = zipEntry.getInputStream()) {
                            byte[] data = ris.readAllBytes();
                            if (data != null) {
                                return new String(data, "UTF-8");
                            }
                        }
                    }
                } catch (Exception e) {
                    throw new RuntimeException("Error loading resource file: " + fileName + " - " + e.getMessage(), e);
                }
            }
        }
        
        return null;
    }
    
    /**
     * Get smali code of a specific class
     */
    public String getSmaliOfClass(String className) {
        checkLoaded();
        JavaClass javaClass = classIndex.get(className);
        if (javaClass != null) {
            try {
                return javaClass.getSmali();
            } catch (Exception e) {
                throw new RuntimeException("Error getting smali for class: " + className + " - " + e.getMessage(), e);
            }
        }
        return null;
    }
    
    /**
     * Get smali code of a specific method
     */
    public String getSmaliOfMethod(String className, String methodName) {
        checkLoaded();
        JavaClass javaClass = classIndex.get(className);
        if (javaClass != null) {
            try {
                String classSmali = javaClass.getSmali();
                if (classSmali != null) {
                    return extractMethodSmali(classSmali, methodName);
                }
            } catch (Exception e) {
                throw new RuntimeException("Error getting smali for method: " + methodName + " in class: " + className + " - " + e.getMessage(), e);
            }
        }
        return null;
    }

    /**
     * Fast search in DEX string constant pools with bytecode-level class+method location.
     * No decompilation needed — scans const-string instructions directly.
     */
    public List<Map<String, Object>> searchDexStrings(String keyword, int limit) {
        checkLoaded();
        List<Map.Entry<String, Set<String>>> matches = new ArrayList<>();
        for (String s : dexStringPool) {
            if (s.contains(keyword)) {
                Set<String> refs = dexStringRefs.getOrDefault(s, Collections.emptySet());
                matches.add(Map.entry(s, refs));
            }
        }
        matches.sort(Comparator.comparing(Map.Entry::getKey));
        if (limit > 0 && matches.size() > limit) {
            matches = matches.subList(0, limit);
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<String, Set<String>> entry : matches) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("string", entry.getKey());
            item.put("references", new ArrayList<>(entry.getValue()));
            result.add(item);
        }
        return result;
    }

    /**
     * Search for a string in decompiled source code.
     * Optimized: checks DEX string pool first, auto-filters by manifest package when no filter provided.
     * Pass packageFilter="*" to force searching all classes.
     */
    public Map<String, List<String>> searchString(String keyword, String packageFilter) {
        return searchString(keyword, packageFilter, 30000, 50);
    }

    public Map<String, List<String>> searchString(String keyword, String packageFilter,
                                                    int timeoutMs, int maxResults) {
        checkLoaded();
        long startTime = System.currentTimeMillis();

        // Fast path: check DEX string pool first
        if (!dexStringPool.isEmpty()) {
            boolean found = false;
            for (String s : dexStringPool) {
                if (s.contains(keyword)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                logger.info("searchString: '" + keyword + "' not in DEX string pool, skipping decompilation");
                Map<String, List<String>> empty = new LinkedHashMap<>();
                empty.put("@searchMeta", List.of("searched=0, total=0, truncated=false, timeMs=0"));
                return empty;
            }
        }

        // Determine effective filter
        String effectiveFilter;
        if ("*".equals(packageFilter)) {
            effectiveFilter = null;
            logger.info("searchString: explicit wildcard, searching ALL classes");
        } else if (packageFilter != null && !packageFilter.isEmpty()) {
            effectiveFilter = packageFilter;
        } else if (packageName != null && !packageName.isEmpty()) {
            effectiveFilter = packageName;
            logger.info("searchString: no packageFilter, auto-using: " + effectiveFilter);
        } else {
            effectiveFilter = null;
        }

        // Count total classes to search
        int totalToSearch = 0;
        for (String className : classIndex.keySet()) {
            if (effectiveFilter == null || className.startsWith(effectiveFilter)) {
                totalToSearch++;
            }
        }

        Map<String, List<String>> results = new LinkedHashMap<>();
        int searched = 0;
        boolean truncated = false;
        String truncateReason = null;

        for (Map.Entry<String, JavaClass> entry : classIndex.entrySet()) {
            String className = entry.getKey();
            if (effectiveFilter != null && !className.startsWith(effectiveFilter)) {
                continue;
            }
            searched++;

            // Check timeout
            if (timeoutMs > 0 && (System.currentTimeMillis() - startTime) > timeoutMs) {
                truncated = true;
                truncateReason = "timeout(" + timeoutMs + "ms)";
                break;
            }
            // Check max results
            if (maxResults > 0 && results.size() >= maxResults) {
                truncated = true;
                truncateReason = "maxResults(" + maxResults + ")";
                break;
            }

            try {
                String code = entry.getValue().getCode();
                if (code != null && code.contains(keyword)) {
                    List<String> matchLines = new ArrayList<>();
                    String[] lines = code.split("\n");
                    for (int i = 0; i < lines.length; i++) {
                        if (lines[i].contains(keyword)) {
                            matchLines.add((i + 1) + ": " + lines[i].trim());
                        }
                    }
                    if (!matchLines.isEmpty()) {
                        results.put(className, matchLines);
                    }
                }
            } catch (Exception e) {
                // skip classes that fail to decompile
            }
            if (searched % 5000 == 0) {
                logger.info("searchString: searched " + searched + "/" + totalToSearch + " classes...");
            }
        }

        long elapsed = System.currentTimeMillis() - startTime;
        logger.info("searchString: done, searched " + searched + "/" + totalToSearch
                + ", found " + results.size() + " matches, time=" + elapsed + "ms"
                + (truncated ? ", truncated=" + truncateReason : ""));

        // Add meta info
        String meta = "searched=" + searched + ", total=" + totalToSearch
                + ", found=" + results.size()
                + ", truncated=" + truncated
                + (truncateReason != null ? ", reason=" + truncateReason : "")
                + ", timeMs=" + elapsed;
        results.put("@searchMeta", List.of(meta));

        return results;
    }

    /**
     * Close the analyzer and free resources
     */
    public void close() {
        stopBackgroundDecompile();
        if (jadx != null) {
            try {
                jadx.close();
            } catch (Exception e) {
                System.err.println("Error closing JADX: " + e.getMessage());
            }
        }
        dexStringPool.clear();
        dexStringRefs.clear();
    }
}