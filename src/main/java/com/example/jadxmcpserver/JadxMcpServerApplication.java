package com.example.jadxmcpserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.logging.Logger;

import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.autoconfigure.r2dbc.R2dbcAutoConfiguration;
import org.springframework.boot.autoconfigure.batch.BatchAutoConfiguration;

@SpringBootApplication(exclude = {
        DataSourceAutoConfiguration.class,
        R2dbcAutoConfiguration.class,
        BatchAutoConfiguration.class
})
public class JadxMcpServerApplication {

    private static final Logger logger = Logger.getLogger(JadxMcpServerApplication.class.getName());

    static {
        System.setProperty("logging.level.root", "INFO");
        System.setProperty("logging.level.org.springframework", "DEBUG");
        System.setProperty("logging.level.org.springframework.ai.mcp", "DEBUG");
    }

    public static void main(String[] args) {
        // --test mode: skip Spring, run CLI test directly
        if (args.length >= 2 && "--test".equals(args[0])) {
            runTest(args);
            return;
        }

        logger.info("Starting JADX MCP Server Application (Multi-Instance Hub)...");

        try {
            SpringApplication app = new SpringApplication(JadxMcpServerApplication.class);
            app.run(args);

            logger.info("JADX MCP Server started successfully");

            Thread.currentThread().join();
        } catch (Exception e) {
            logger.severe("Failed to start MCP server: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }

    /**
     * CLI test mode: java -jar jadx-mcp-server.jar --test <apk> [keyword] [--full-decompile]
     */
    private static void runTest(String[] args) {
        String apkPath = args[1];
        String keyword = args.length >= 3 && !args[2].startsWith("--") ? args[2] : null;
        boolean fullDecompile = false;
        boolean bgDecompile = false;
        for (String arg : args) {
            if ("--full-decompile".equals(arg)) fullDecompile = true;
            if ("--bg-decompile".equals(arg)) bgDecompile = true;
        }

        System.out.println("=== JADX MCP Server - Test Mode ===");
        System.out.println("APK: " + apkPath);
        System.out.println();

        com.example.jadxmcpserver.core.JadxAnalyzerCore core =
                new com.example.jadxmcpserver.core.JadxAnalyzerCore(apkPath);

        try {
            // Load APK (timings are printed inside)
            long t0 = System.currentTimeMillis();
            core.loadApk();
            long t1 = System.currentTimeMillis();

            java.util.Map<String, Object> info = core.getApkInfo();
            System.out.println("Package: " + info.get("packageName"));
            System.out.println("Total classes: " + info.get("totalClasses"));
            System.out.println("Load timings: " + info.get("loadTimings"));
            System.out.println("Total loadApk: " + (t1 - t0) + "ms");
            System.out.println();

            // DEX string search
            if (keyword != null) {
                System.out.println("--- search_dex_strings(\"" + keyword + "\") ---");
                long t2 = System.currentTimeMillis();
                java.util.List<java.util.Map<String, Object>> results = core.searchDexStrings(keyword, 20);
                long t3 = System.currentTimeMillis();
                System.out.println("Time: " + (t3 - t2) + "ms, matches: " + results.size());
                for (java.util.Map<String, Object> m : results) {
                    System.out.println("  \"" + m.get("string") + "\" -> " + m.get("references"));
                }
                System.out.println();
            }

            // Watch background decompile progress
            if (bgDecompile) {
                System.out.println("--- Background decompile progress ---");
                while (true) {
                    java.util.Map<String, Object> status = core.getDecompileStatus();
                    System.out.println("  " + status.get("progress") + "/" + status.get("total")
                            + " (" + status.get("percent") + ")"
                            + (status.get("eta") != null ? " eta=" + status.get("eta") : "")
                            + " failed=" + status.get("failed"));
                    if (Boolean.TRUE.equals(status.get("done"))) break;
                    Thread.sleep(3000);
                }
                System.out.println("Background decompile complete.");
                System.out.println();
            }

            // Full decompile benchmark
            if (fullDecompile) {
                System.out.println("--- Full decompile benchmark (foreground) ---");
                java.util.List<String> allClasses = core.getAllClasses();
                int total = allClasses.size();
                int success = 0, fail = 0;
                long td0 = System.currentTimeMillis();
                for (int i = 0; i < total; i++) {
                    try {
                        String code = core.getClassSource(allClasses.get(i));
                        if (code != null) success++; else fail++;
                    } catch (Exception e) {
                        fail++;
                    }
                    if ((i + 1) % 5000 == 0) {
                        long elapsed = System.currentTimeMillis() - td0;
                        System.out.println("  " + (i + 1) + "/" + total + " (" + elapsed + "ms)");
                    }
                }
                long td1 = System.currentTimeMillis();
                System.out.println("Full decompile: " + total + " classes, " + success + " ok, " + fail + " failed");
                System.out.println("Time: " + (td1 - td0) + "ms (" + ((td1 - td0) / 1000) + "s)");
            }
        } catch (Exception e) {
            System.err.println("Error: " + e.getMessage());
            e.printStackTrace();
        } finally {
            core.close();
        }
    }

    @Bean
    public InstanceManager instanceManager() {
        return new InstanceManager();
    }

    @Bean
    public ToolCallbackProvider jadxTools(JadxToolService jadxService) {
        logger.info("Registering JADX tools (multi-instance hub)...");
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder()
                .toolObjects(jadxService)
                .build();
        logger.info("Registered tool callbacks: " + provider.getToolCallbacks());
        return provider;
    }
}
