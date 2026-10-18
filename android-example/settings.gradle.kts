pluginManagement { repositories { google(); gradlePluginPortal(); mavenCentral() } }
dependencyResolutionManagement { repositories { google(); mavenCentral() } }
rootProject.name = "OSRMRouteAndroidExample"
include(":osrmroute-kotlin")
project(":osrmroute-kotlin").projectDir = file("..")
