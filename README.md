# JADX Multi-Instance Hub

A **Pure-Java** MCP server for JADX with **multi-instance hub** support — load and analyze multiple APKs simultaneously from a single MCP connection, inspired by [IDA Pro MCP](https://github.com/mrexodia/ida-pro-mcp)'s hub architecture.

> Fork of [Qtty/jadx-mcp-server](https://github.com/Qtty/jadx-mcp-server), enhanced with multi-instance target routing.

## Features

- **Multi-Instance Hub** — Load multiple APKs at once, route queries via `target` parameter
- **Zero GUI Required** — JADX as library, no need to open any JADX-GUI window
- **Single JAR Deployment** — One process manages all APK instances
- **Target Routing** — `select_target`, `list_instances`, per-tool `target` parameter (like IDA MCP)
- **Pure Java** — No native dependencies, runs on any Java 17+ platform

## Quick Comparison

| Feature | jadx-mcp-server (original) | **jadx-multi-instance-hub** | jadx-ai-mcp (zinja) |
|---------|---------------------------|----------------------------|---------------------|
| Multi-APK | No | **Yes** | No |
| Needs GUI | No | **No** | Yes (JADX-GUI) |
| Hub mode | No | **Yes** | No |
| Target routing | No | **Yes** | No |

## Installation

### Build

```bash
# Requires Java 17+ and Maven 3.6+
mvn clean package -DskipTests
```

### Configure Claude Code

```bash
claude mcp add jadx-hub "D:\Program Files\Java\jdk-17.0.10\bin\java.exe" -- -jar "D:\path\to\jadx-mcp-server\target\jadx-mcp-server-1.0.0.jar"
```

Or manually add to your Claude config:

```json
{
  "jadx-hub": {
    "type": "stdio",
    "command": "java",
    "args": ["-jar", "/path/to/jadx-mcp-server-1.0.0.jar"]
  }
}
```

## Usage

### Load Multiple APKs

```
load_apk("D:/apks/app_v1.apk", "app-v1")
load_apk("D:/apks/app_v2.apk", "app-v2")
```

### Query with Target Routing

```
# Query specific instance
get_class_source("com.example.MainActivity", "app-v1")
get_class_source("com.example.MainActivity", "app-v2")

# Use default target (auto-selected when only one instance, or set manually)
select_target("app-v2")
get_class_source("com.example.MainActivity")
```

### Manage Instances

```
list_instances()        # Show all loaded APKs
current_target()        # Show current default
select_target("app-v1") # Switch default
remove_instance("app-v1") # Unload APK
```

## MCP Tools

### Instance Management (5 tools)

| Tool | Description |
|------|-------------|
| `load_apk` | Load APK with a named instance ID |
| `list_instances` | List all loaded instances |
| `select_target` | Set default target instance |
| `current_target` | Show current default target |
| `remove_instance` | Close and remove an instance |

### Analysis Tools (13 tools, all support `target` parameter)

| Tool | Description |
|------|-------------|
| `get_all_classes` | List all classes |
| `get_class_source` | Decompiled Java source |
| `get_methods_of_class` | List methods |
| `get_fields_of_class` | List fields |
| `get_method_by_name` | Method source code |
| `search_method_by_name` | Search methods across classes |
| `get_exported_components` | Exported Android components |
| `get_android_manifest` | AndroidManifest.xml |
| `get_main_activity_class` | Main launcher activity |
| `get_all_resource_file_names` | Resource file listing |
| `get_resource_file` | Resource file content |
| `get_smali_of_class` | Smali bytecode (class) |
| `get_smali_of_method` | Smali bytecode (method) |

## Target Routing Logic

- **1 instance loaded** — auto-selects, no `target` needed
- **Multiple instances, default set** — uses default
- **Multiple instances, no default** — error prompts you to `select_target`
- **Explicit `target` parameter** — always uses specified instance

## Architecture

```
Claude Code / AI Client
        |
        | MCP (stdio)
        v
+-------------------+
|  JadxToolService  |  <-- 18 MCP tools with target routing
+-------------------+
        |
+-------------------+
|  InstanceManager  |  <-- Map<instanceId, JadxApkAnalyzerAPI>
+-------------------+
    /       \
   v         v
[app-v1]  [app-v2]   <-- Independent JadxDecompiler instances
```

## Use Case: APK Version Diffing

Perfect for comparing obfuscated code across app versions:

```
load_apk("app_v23.60.apk", "old")
load_apk("app_v23.64.apk", "new")

# Compare same class across versions
get_class_source("com.example.Security", "old")
get_class_source("com.example.Security", "new")

# Search for renamed/obfuscated methods
search_method_by_name("sendReport", "old")
search_method_by_name("sendReport", "new")  # Gone? Check obfuscation
```

## Requirements

- Java 17+
- Maven 3.6+ (build only)

## Credits

- [Qtty/jadx-mcp-server](https://github.com/Qtty/jadx-mcp-server) — Original single-instance implementation
- [mrexodia/ida-pro-mcp](https://github.com/mrexodia/ida-pro-mcp) — Hub architecture inspiration
- [skylot/jadx](https://github.com/skylot/jadx) — JADX decompiler engine

## License

Apache License 2.0 (same as JADX)
